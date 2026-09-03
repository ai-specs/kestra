package io.kestra.oidc.controllers;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcClientService;
import io.kestra.oidc.services.OidcSessionService;
import io.kestra.oidc.services.OidcUserService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator;
import jakarta.inject.Inject;

/**
 * The OIDC Provider's own login surface: the IdP login page both Nacos SSO and the Kestra
 * self-bootstrap land on, plus the {@code kestra-self} client's authorization-code callback.
 *
 * <p>
 * This is the piece that lets the BROWSER complete the whole flow. The provider deliberately does
 * <b>not</b> use Kestra's Basic Auth (Basic Auth re-sends credentials on every request and is
 * banned here). A successful {@code POST /oidc/login} validates against the configured
 * {@code kestra.oidc.admin-username}/{@code admin-password} through {@link OidcUserService} and
 * returns a single opaque {@code oidc_session} cookie from {@link OidcSessionService}. The
 * authorize endpoint then resolves the user from that session cookie alone.
 *
 * <p>
 * {@code GET /oidc/self-login} makes Kestra a client of its own provider: it redirects to
 * {@code /oidc/authorize} for the seeded {@code kestra-self} client, and
 * {@code GET /oidc/callback} consumes the returned code server-side before landing on the UI.
 */
@Controller("/oidc")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcLoginController {

    /** The self-bootstrap client seeded by the oidc-provider migration. */
    static final String SELF_CLIENT_ID = "kestra-self";

    /** Fallback landing page when a login carries no {@code from}. */
    static final String DEFAULT_LANDING = "/ui/";

    private final OidcConfiguration configuration;
    private final OidcAuthorizationCodeService authCodeService;
    private final OidcClientService clientService;
    private final OidcUserService userService;
    private final OidcSessionService sessionService;
    private final Optional<JwtTokenGenerator> jwtTokenGenerator;

    @Inject
    public OidcLoginController(
        OidcConfiguration configuration,
        OidcAuthorizationCodeService authCodeService,
        OidcClientService clientService,
        OidcUserService userService,
        OidcSessionService sessionService,
        Optional<JwtTokenGenerator> jwtTokenGenerator
    ) {
        this.configuration = configuration;
        this.authCodeService = authCodeService;
        this.clientService = clientService;
        this.userService = userService;
        this.sessionService = sessionService;
        this.jwtTokenGenerator = jwtTokenGenerator;
    }

    // ------------------------------------------------------------------ login page

    /**
     * The IdP login form. {@code from} carries the authorize request (or any same-origin page)
     * to return to after a successful login.
     */
    @Get("/login")
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<String> loginPage(@io.micronaut.http.annotation.QueryValue Optional<String> from,
                                          @io.micronaut.http.annotation.QueryValue Optional<String> error) {
        return HttpResponse.ok(loginPageHtml(sanitizeFrom(from.orElse(null)), error.isPresent()))
            .contentType(MediaType.TEXT_HTML_TYPE);
    }

    /**
     * Validate the submitted credentials and, on success, create a provider session and set the
     * {@code oidc_session} cookie (HttpOnly, SameSite=Strict) then redirect back to {@code from}
     * (303 so a browser refresh does not re-post the password). Credentials are exchanged for the
     * session cookie once and never re-sent on later requests.
     *
     * <p>
     * When Kestra runs with Micronaut Security enabled (its own UI/API auth), a JWT cookie is
     * also issued here — the same login therefore authenticates the OIDC authorize endpoint
     * (via {@code oidc_session}) and the Kestra UI/API (via the {@code JWT} cookie that
     * SecurityFilter validates). Neither involves Basic Auth.
     */
    @Post("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> loginSubmit(HttpRequest<?> request, @io.micronaut.http.annotation.Body Map<String, String> form) {
        String username = form.get("username");
        String password = form.get("password");
        String from = sanitizeFrom(form.get("from"));

        String subject = username == null ? "" : username.trim();
        boolean valid = userService.validateCredentials(subject, password);
        if (!valid) {
            return HttpResponse.ok(loginPageHtml(from, true))
                .contentType(MediaType.TEXT_HTML_TYPE)
                .status(io.micronaut.http.HttpStatus.UNAUTHORIZED);
        }

        String sessionId = sessionService.create(subject);
        io.micronaut.http.MutableHttpResponse<?> response = HttpResponse.seeOther(URI.create(from))
            .cookie(sessionService.sessionCookie(request, sessionId));
        // Kestra UI 的 JWT cookie 角色必须与该账号在 IdP 的角色一致（bySubject：
        // kestra.oidc.users 里的角色；管理员/未知主体回落 defaultRoles）——
        // 否则普通用户登录后会被当成 admin。
        jwtTokenGenerator.ifPresent(generator -> generator
            .generateToken(
                Authentication.build(subject, userService.bySubject(subject).roles()),
                Math.toIntExact(configuration.getSessionTtl().toSeconds()))
            .ifPresent(token -> response.cookie(jwtCookie(request, token))));
        // UI 的 SPA 引导守卫读这个非 HttpOnly 标志 cookie 判定「已登录」（上游 OSS 语义：
        // utils/basicAuth isLoggedIn）；没有它即使 JWT 已落地，/ui/ 仍会被路由到登录页。
        response.cookie(uiAuthFlagCookie(request));
        return response;
    }

    /**
     * The {@code JWT} cookie Kestra's Micronaut SecurityFilter validates (default cookie name).
     * Same attributes as the session cookie: HttpOnly, SameSite=Strict, TLS-only when applicable.
     */
    private static Cookie jwtCookie(HttpRequest<?> request, String token) {
        return Cookie.of("JWT", token)
            .path("/")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict);
    }

    /**
     * Non-HttpOnly mirror of the login state, same contract as Kestra's Basic Auth flag cookie
     * ({@code kestraBasicAuthenticated}): the UI's boot guard reads it client-side to decide the
     * browser is logged in before it loads the authenticated configuration. Carries no credentials.
     */
    private static Cookie uiAuthFlagCookie(HttpRequest<?> request) {
        return Cookie.of("kestraBasicAuthenticated", "true")
            .path("/")
            .httpOnly(false)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict);
    }

    // ------------------------------------------------------------------ self-bootstrap

    /**
     * Kestra as a client of its own provider: redirect the browser into the authorization code
     * flow for the seeded {@code kestra-self} client. Unauthenticated browsers then hit the
     * login form above, come back here with the session cookie, and receive a code for
     * {@code /oidc/callback}.
     */
    @Get("/self-login")
    public HttpResponse<?> selfLogin() {
        Optional<OidcClientService.OidcClient> client = clientService.find(SELF_CLIENT_ID);
        if (client.isEmpty()) {
            return HttpResponse.badRequest("self-bootstrap client not configured: " + SELF_CLIENT_ID);
        }
        String redirectUri = client.get().redirectUris().isEmpty()
            ? configuration.getExternalBaseUrl() + "/oidc/callback"
            : client.get().redirectUris().get(0);

        String authorize = configuration.getExternalBaseUrl() + "/oidc/authorize"
            + "?response_type=code"
            + "&client_id=" + urlEncode(SELF_CLIENT_ID)
            + "&redirect_uri=" + urlEncode(redirectUri)
            + "&scope=" + urlEncode("openid profile email")
            + "&state=" + urlEncode(randomState());
        return HttpResponse.redirect(URI.create(authorize));
    }

    /**
     * The canonical {@code kestra-self} redirect target: consume the authorization code
     * server-side — the browser session is the {@code oidc_session} cookie established at the
     * login form, the code only proves this authorization round completed — and land on the UI,
     * logged in.
     *
     * <p>
     * The path deliberately lives under the provider's own {@code /oidc/**} namespace: the
     * framework's native OAuth2 client registers {@code /oauth/callback{/provider}} (a
     * PathSegment template — it matches the bare {@code /oauth/callback} root AND any depth
     * of sub-path, not just one segment) as soon as ANY client — third-party IdPs included —
     * is configured, so nothing of ours may live anywhere under {@code /oauth/**}.
     */
    @Get("/callback")
    public HttpResponse<?> callback(@io.micronaut.http.annotation.QueryValue Optional<String> code,
                                    @io.micronaut.http.annotation.QueryValue Optional<String> error) {
        if (error.isPresent()) {
            return HttpResponse.badRequest("authorization failed: " + error.get());
        }
        if (code.isEmpty() || code.get().isBlank()) {
            return HttpResponse.badRequest("missing authorization code");
        }
        Optional<OidcClientService.OidcClient> client = clientService.find(SELF_CLIENT_ID);
        if (client.isEmpty() || client.get().redirectUris().isEmpty()) {
            return HttpResponse.badRequest("self-bootstrap client not configured: " + SELF_CLIENT_ID);
        }
        String redirectUri = client.get().redirectUris().get(0);
        try {
            authCodeService.consume(code.get(), SELF_CLIENT_ID, redirectUri, null);
        } catch (Exception e) {
            return HttpResponse.badRequest("invalid authorization code: " + e.getMessage());
        }
        return HttpResponse.redirect(URI.create(DEFAULT_LANDING));
    }

    /**
     * Clear the OIDC session and the Kestra JWT/flag cookies, then redirect to the IdP login.
     * The UI's logout action navigates here (override {@code Auth.vue}) because Kestra's own
     * {@code POST /logout} (MiscController) only clears its Basic Auth cookies — it leaves
     * {@code oidc_session} and {@code JWT} intact. That would strand the browser in a
     * half-logged-out state: the UI boot guard sees no {@code kestraBasicAuthenticated} flag and
     * routes to the unusable Basic Auth {@code /ui/login} page while the JWT still authenticates
     * the API (SecurityFilter keeps letting {@code /ui/} through).
     *
     * <p>
     * RP-initiated logout: {@code post_logout_redirect_uri} sends the caller (e.g. dsh-ui's
     * 我的 page) back to its own entry instead of the IdP login. The URI is honored only when it
     * is a registered client redirect_uri — otherwise the default IdP login is used, so the
     * parameter cannot become an open redirect.
     */
    @Get("/logout")
    public HttpResponse<?> logout(HttpRequest<?> request) {
        boolean secure = request.isSecure();
        // Read the raw query parameter (not method binding): deterministic across Micronaut versions.
        Optional<String> postLogoutRedirectUri = request.getParameters().getFirst("post_logout_redirect_uri");
        URI target = postLogoutRedirectUri
            .filter(this::isRegisteredRedirectUri)
            .map(URI::create)
            .orElseGet(() -> URI.create(configuration.getExternalBaseUrl() + "/oidc/login"));
        return HttpResponse.redirect(target)
            .cookie(clearCookie(OidcSessionService.SESSION_COOKIE_NAME, secure))
            .cookie(clearCookie("JWT", secure))
            .cookie(clearCookie("kestraBasicAuthenticated", secure));
    }

    private boolean isRegisteredRedirectUri(String uri) {
        return clientService.list().stream()
            .anyMatch(client -> client.redirectUris().contains(uri));
    }

    private static Cookie clearCookie(String name, boolean secure) {
        return Cookie.of(name, "")
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite(SameSite.Strict)
            .maxAge(0);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Same-origin {@code from} guard: only absolute-path references survive (an absolute URL or
     * a protocol-relative {@code //host} would turn the login into an open redirect).
     */
    static String sanitizeFrom(String from) {
        if (from == null || from.isBlank()) return DEFAULT_LANDING;
        if (!from.startsWith("/") || from.startsWith("//") || from.contains("://")) return DEFAULT_LANDING;
        return from;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ page

    /** Minimal self-contained login page; every interpolated value is HTML-escaped. */
    static String loginPageHtml(String from, boolean error) {
        return """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>dsh 统一登录 · Kestra OIDC</title>
            <style>
              :root { color-scheme: dark; }
              body { font-family: system-ui, -apple-system, sans-serif; background: #0b1020; color: #e6e9f2;
                     display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
              .card { background: #141a2e; border: 1px solid #26304d; border-radius: 14px; padding: 36px;
                      width: 360px; box-shadow: 0 10px 40px rgba(0,0,0,.45); }
              h1 { font-size: 18px; margin: 0 0 4px; }
              p.sub { color: #8b93ab; font-size: 13px; margin: 0 0 24px; }
              label { display: block; font-size: 13px; color: #aab2c8; margin: 14px 0 6px; }
              input { width: 100%%; box-sizing: border-box; padding: 10px 12px; border-radius: 8px;
                      border: 1px solid #2c3757; background: #0e1426; color: #e6e9f2; font-size: 14px; }
              input:focus { outline: none; border-color: #4f7cff; }
              button { width: 100%%; margin-top: 22px; padding: 11px; border: 0; border-radius: 8px;
                       background: #2952e3; color: #fff; font-size: 15px; font-weight: 600; cursor: pointer; }
              button:hover { background: #1e46c2; }
              .error { color: #ff8080; font-size: 13px; margin: 14px 0 0; min-height: 16px; }
            </style>
            </head>
            <body>
            <div class="card">
              <h1>dsh 统一登录</h1>
              <p class="sub">Kestra OIDC Provider（企业统一 IdP）</p>
              <form method="post" action="/oidc/login">
                <input type="hidden" name="from" value="%s">
                <label for="username">用户名</label>
                <input id="username" name="username" type="text" autocomplete="username" autofocus required>
                <label for="password">密码</label>
                <input id="password" name="password" type="password" autocomplete="current-password" required>
                <p class="error">%s</p>
                <button type="submit">登录</button>
              </form>
            </div>
            </body>
            </html>
            """.formatted(htmlEscape(from), error ? "用户名或密码错误" : "");
    }

    /** Escapes the five characters that matter in an HTML text/attribute context. */
    static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
