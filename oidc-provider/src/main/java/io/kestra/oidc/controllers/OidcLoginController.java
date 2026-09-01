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
import io.kestra.webserver.services.BasicAuthService;

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
import jakarta.inject.Inject;

/**
 * The OIDC Provider's own login surface: the IdP login page both Nacos SSO and the Kestra
 * self-bootstrap land on, plus the {@code kestra-self} client's authorization-code callback.
 *
 * <p>
 * This is the piece that lets the BROWSER complete the whole flow without touching upstream
 * auth code: {@code BasicAuthService} already accepts a {@code BASIC_AUTH} cookie as a valid
 * session, and the upstream login endpoint already issues exactly that cookie pair. This
 * controller serves an HTML login form at {@code /oidc/login} (the {@code kestra.oidc.login-url}
 * target the authorize endpoint redirects to), validates credentials through the same
 * {@link BasicAuthService#validateCredentials}, and issues the identical cookie pair — so one
 * login simultaneously authenticates the OIDC authorize endpoint and the Kestra UI/API.
 *
 * <p>
 * {@code GET /oidc/self-login} then makes Kestra a client of its own provider: it redirects to
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
    private final Optional<BasicAuthService> basicAuthService;

    @Inject
    public OidcLoginController(
        OidcConfiguration configuration,
        OidcAuthorizationCodeService authCodeService,
        OidcClientService clientService,
        Optional<BasicAuthService> basicAuthService
    ) {
        this.configuration = configuration;
        this.authCodeService = authCodeService;
        this.clientService = clientService;
        this.basicAuthService = basicAuthService;
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
     * Validate the submitted credentials and, on success, set the BASIC_AUTH cookie pair
     * (identical attributes to the upstream login endpoint) and redirect back to {@code from}
     * (303 so a browser refresh does not re-post the password).
     */
    @Post("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> loginSubmit(HttpRequest<?> request, @io.micronaut.http.annotation.Body Map<String, String> form) {
        String username = form.get("username");
        String password = form.get("password");
        String from = sanitizeFrom(form.get("from"));

        boolean valid = basicAuthService
            .map(service -> service.validateCredentials(username == null ? "" : username.trim(), password))
            .orElse(false);
        if (!valid) {
            return HttpResponse.ok(loginPageHtml(from, true))
                .contentType(MediaType.TEXT_HTML_TYPE)
                .status(io.micronaut.http.HttpStatus.UNAUTHORIZED);
        }

        return HttpResponse.seeOther(URI.create(from))
            .cookie(authCookie(request, username.trim(), password))
            .cookie(authFlagCookie(request));
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
     * The {@code kestra-self} redirect target: consume the authorization code server-side (the
     * browser session is the BASIC_AUTH cookie established at the login form, the code only
     * proves this authorization round completed) and land on the UI, logged in.
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

    /** {@code BASIC_AUTH} session cookie with the exact attributes the upstream login endpoint issues. */
    private static Cookie authCookie(HttpRequest<?> request, String username, String password) {
        return Cookie.of(BasicAuthService.BASIC_AUTH_COOKIE_NAME, BasicAuthService.encodeToken(username, password))
            .path("/")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict);
    }

    /** The non-HttpOnly flag cookie the UI reads to know it is logged in. */
    private static Cookie authFlagCookie(HttpRequest<?> request) {
        return Cookie.of(BasicAuthService.BASIC_AUTH_FLAG_COOKIE_NAME, "true")
            .path("/")
            .httpOnly(false)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict);
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
