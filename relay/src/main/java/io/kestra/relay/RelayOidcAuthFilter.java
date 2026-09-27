package io.kestra.relay;

import java.util.Map;
import java.util.Set;

import io.micronaut.context.annotation.Requires;
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
 * Guards the relay surface ({@code /api/v1/dsh/relay/**}) with a Bearer access token issued by
 * the Kestra OIDC provider. Self-contained copy of the oidc-provider module's
 * OidcBearerAuthFilter (2026-09-27): the relay process does not depend on that module, so the
 * guard, its token service and the CORS preflight handling live here. The relay is reached
 * cross-origin by the H5 mobile client, so the preflight is answered here from the configured
 * origin allow-list exactly like the main server does.
 *
 * <p>
 * Validated claims are stashed on the request under {@link #CLAIMS_ATTRIBUTE}; controllers read
 * {@code sub}/{@code client_id}/{@code roles} via {@link DshIdentity}. Runs FIRST (ORDER -1000)
 * so the relay's own guard is the sole gatekeeper.
 */
@Filter(patternStyle = FilterPatternStyle.ANT, value = {
    "/api/v1/dsh/relay/**"
})
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class RelayOidcAuthFilter implements HttpServerFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> DSH_AUDIENCES = Set.of("dsh", "dsh-ui", "dsh-pc");

    public static final int ORDER = -1000;

    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";

    /** Attribute under which the validated access-token claims are stashed on the request. */
    public static final String CLAIMS_ATTRIBUTE = "io.kestra.relay.claims";

    private final RelayOidcTokenService tokenService;
    private final RelayOidcConfiguration configuration;

    @Inject
    public RelayOidcAuthFilter(RelayOidcTokenService tokenService, RelayOidcConfiguration configuration) {
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

        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Publishers.just(unauthorized("missing_token",
                "Authorization: Bearer <oidc access token> is required (POST /oidc/token)"));
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            var claims = tokenService.validateAccessToken(token);
            if (claims.getAudience() == null || claims.getAudience().stream().noneMatch(DSH_AUDIENCES::contains)) {
                return Publishers.just(unauthorized("invalid_audience",
                    "this token's audience is not a dsh ecosystem client (dsh/dsh-ui/dsh-pc)"));
            }
            request.getAttributes().put(CLAIMS_ATTRIBUTE, claims.toJSONObject());
            return chain.proceed(request);
        } catch (Exception e) {
            return Publishers.just(unauthorized("invalid_token", e.getMessage()));
        }
    }

    private static MutableHttpResponse<?> unauthorized(String error, String description) {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"")
            .body(Map.of("error", error, "error_description", description));
    }
}
