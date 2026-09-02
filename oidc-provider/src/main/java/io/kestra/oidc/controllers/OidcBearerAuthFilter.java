package io.kestra.oidc.controllers;

import java.util.Map;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.OidcTokenService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.FilterPatternStyle;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;

import org.reactivestreams.Publisher;

import jakarta.inject.Inject;

/**
 * Guards the dsh ecosystem APIs with a token issued by this OIDC Provider — the same IdP that
 * authenticates the Kestra UI and Nacos SSO.
 *
 * <p>
 * Protected surfaces:
 * <ul>
 *   <li>{@code /api/v1/dsh/**} — sessions/approvals/metrics/gateway (callers: dsh (PC) plugins,
 *       dsh-ui, scripts); gateway endpoints share the same Bearer guard;</li>
 *   <li>{@code /api/v1/executions/dsh/**} — triggering flows in the {@code dsh} namespace (the
 *       AIAgent execution plane), so dsh-ui can start tasks with its provider token.</li>
 * </ul>
 *
 * <p>
 * Callers obtain an access token from {@code POST /oidc/token}: user identities via
 * authorization code + PKCE(S256) (public clients {@code dsh-ui}/{@code dsh-pc}), service
 * identities via client_credentials (the seeded {@code dsh} client). Validation is the
 * provider's own {@link OidcTokenService#validateAccessToken}: RS256 signature against the
 * published JWK, issuer, expiry, revocation and token type.
 *
 * <p>
 * The validated claims are stashed on the request for the controllers (session ownership by
 * OIDC sub, dsh.docx 跨端同步原理). This is the CLASSIC {@link HttpServerFilter} interface on
 * purpose: with the annotation-style {@code @RequestFilter} the mutated request instance was
 * not the one seen by the route, so stashed claims silently never arrived.
 */
@Filter(patternStyle = FilterPatternStyle.ANT, value = {
    "/api/v1/dsh/**", "/api/v1/executions/dsh/**",
    "/oidc/token", "/oidc/userinfo", "/.well-known/openid-configuration"
})
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcBearerAuthFilter implements HttpServerFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Attribute under which the validated access-token claims are stashed on the request.
     * Controllers on the dsh surfaces read {@code sub} (the owner identity for session records),
     * {@code client_id} and {@code roles} from it.
     */
    public static final String CLAIMS_ATTRIBUTE = "io.kestra.oidc.claims";

    /**
     * The dsh ecosystem clients: access tokens minted for any of them may reach the dsh APIs —
     * {@code dsh} (service identity: dsh(PC) plugins, AIAgent containers, scripts),
     * {@code dsh-ui} (mobile) and {@code dsh-pc} (user PC), the latter two authorization code +
     * PKCE. A token minted for another client (e.g. the nacos config client) must not reach
     * these surfaces — the audience check is the sole gateway authorization factor.
     */
    private static final java.util.Set<String> DSH_AUDIENCES = java.util.Set.of("dsh", "dsh-ui", "dsh-pc");

    /**
     * Runs FIRST (before Micronaut's SecurityFilter and the generic CORS filter): on the dsh
     * surfaces the provider is the sole gatekeeper, and a preflight answer must not depend on
     * filter-ordering internals. Empirically an order-100 filter never saw the preflight —
     * something between rejected it with a bare 403.
     */
    public static final int ORDER = -1000;

    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";

    private final OidcTokenService tokenService;
    private final OidcConfiguration configuration;

    @Inject
    public OidcBearerAuthFilter(OidcTokenService tokenService, OidcConfiguration configuration) {
        this.tokenService = tokenService;
        this.configuration = configuration;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String origin = request.getHeaders().get("Origin");
        boolean originAllowed = origin != null && configuration.getCorsAllowedOrigins().contains(origin);

        // CORS preflight is answered HERE, from the configured origin allow-list: the generic
        // CORS-filter ordering interacts badly with the route guard (bare 403 before this filter
        // ever ran), and the dsh APIs must give the browser a deterministic preflight answer.
        // This also covers /oidc/token etc. because the H5 PKCE client calls them cross-origin
        // while micronaut.server.cors stays disabled (see docker-compose.yml).
        if (io.micronaut.http.HttpMethod.OPTIONS == request.getMethod()
            && request.getHeaders().get(ACCESS_CONTROL_REQUEST_METHOD) != null) {
            if (!originAllowed) {
                return Publishers.just(HttpResponse.status(HttpStatus.FORBIDDEN));
            }
            MutableHttpResponse<?> response = HttpResponse.ok();
            response.header(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.header(ACCESS_CONTROL_ALLOW_METHODS, request.getHeaders().get(ACCESS_CONTROL_REQUEST_METHOD));
            String requestedHeaders = request.getHeaders().get(ACCESS_CONTROL_REQUEST_HEADERS);
            if (requestedHeaders != null) {
                response.header(ACCESS_CONTROL_ALLOW_HEADERS, requestedHeaders);
            }
            response.header(ACCESS_CONTROL_MAX_AGE, "1800");
            return Publishers.just(response);
        }

        // The token/userinfo/discovery surfaces are NOT bearer-guarded (public client token
        // requests carry client_id in the body, no secret) — only their CORS response headers
        // are managed here.
        if (!isGuardedPath(request.getPath())) {
            return withCorsHeaders(chain.proceed(request), origin, originAllowed);
        }

        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return withCorsHeaders(Publishers.just(unauthorized("missing_token",
                "Authorization: Bearer <oidc access token> is required (POST /oidc/token)")), origin, originAllowed);
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            var claims = tokenService.validateAccessToken(token);
            if (claims.getAudience() == null || claims.getAudience().stream().noneMatch(DSH_AUDIENCES::contains)) {
                return withCorsHeaders(Publishers.just(unauthorized("invalid_audience",
                    "this token's audience is not a dsh ecosystem client (dsh/dsh-ui/dsh-pc)")), origin, originAllowed);
            }
            // Stored as a plain Map (net.minidev.json.JSONObject implements Map): the webserver
            // module has no Nimbus dependency and reads the claims generically.
            // NOTE: identical propagation to Kestra's McpServerAuthenticationFilter —
            // unconditional getAttributes().put, read back with the two-arg getAttribute.
            request.getAttributes().put(CLAIMS_ATTRIBUTE, claims.toJSONObject());
            return withCorsHeaders(chain.proceed(request), origin, originAllowed);
        } catch (Exception e) {
            return withCorsHeaders(Publishers.just(unauthorized("invalid_token", e.getMessage())), origin, originAllowed);
        }
    }

    /** Guarded routes require the provider-issued Bearer; anything else this filter touches is CORS-only. */
    private static boolean isGuardedPath(String path) {
        return path.startsWith("/api/v1/dsh/") || path.startsWith("/api/v1/executions/dsh/");
    }

    /** Echoes the origin into the downstream response when it is an allowed CORS origin. */
    private Publisher<MutableHttpResponse<?>> withCorsHeaders(Publisher<MutableHttpResponse<?>> downstream, String origin, boolean originAllowed) {
        if (origin == null || !originAllowed) {
            return downstream;
        }
        return Publishers.map(downstream, response -> {
            response.header(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            return response;
        });
    }

    /** RFC 6750 style 401: challenge header plus a machine-readable reason. */
    private static MutableHttpResponse<?> unauthorized(String error, String description) {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"")
            .body(Map.of("error", error, "error_description", description));
    }
}
