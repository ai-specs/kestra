package io.kestra.oidc.controllers;

import java.util.Map;

import io.kestra.oidc.services.OidcTokenService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.FilterPatternStyle;

import jakarta.inject.Inject;

/**
 * Guards the dsh ecosystem APIs with a token issued by this OIDC Provider — the same IdP that
 * authenticates the Kestra UI and Nacos SSO.
 *
 * <p>
 * Protected surfaces:
 * <ul>
 *   <li>{@code /api/v1/dsh/**} — sessions/approvals/metrics/gateway (callers: dsh (PC) plugins,
 *       dsh-ui's BFF, scripts); the gateway endpoints additionally require
 *       {@code X-Dsh-Gateway-Token};</li>
 *   <li>{@code /api/v1/executions/dsh/**} — triggering flows in the {@code dsh} namespace (the
 *       AIAgent execution plane), so dsh-ui can start tasks with its provider token without a
 *       Kestra admin session.</li>
 * </ul>
 *
 * <p>
 * Callers obtain an access token from {@code POST /oidc/token} — the machine-to-machine path is
 * the {@code client_credentials} grant for the seeded {@code dsh} client; dsh-ui uses the
 * authorization-code flow with the same client. Validation is the provider's own
 * {@link OidcTokenService#validateAccessToken}: RS256 signature against the published JWK,
 * issuer, expiry, revocation and token type.
 *
 * <p>
 * Known limitation: browser sessions (the Kestra UI console) hold a JWT cookie, not a provider
 * token — triggering {@code dsh}-namespace flows from the Kestra console requires a Bearer token
 * (dsh-ui and scripts are the designated trigger surfaces). A principal-based fallback was
 * rejected: Micronaut populates a principal for ANONYMOUS requests too, which silently disabled
 * the guard.
 */
@ServerFilter(patternStyle = FilterPatternStyle.ANT, value = {"/api/v1/dsh/**", "/api/v1/executions/dsh/**"})
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcBearerAuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * The only audience allowed on the dsh ecosystem APIs: the {@code dsh} OIDC client
     * (authorization-code for dsh-ui, client_credentials for dsh(PC)/scripts). A token minted
     * for another client (e.g. the nacos config client) must not reach these surfaces — the
     * audience check replaces the retired static X-Dsh-Gateway-Token factor.
     */
    private static final String REQUIRED_AUDIENCE = "dsh";

    private final OidcTokenService tokenService;

    @Inject
    public OidcBearerAuthFilter(OidcTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(@NonNull HttpRequest<?> request) {
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return unauthorized("missing_token", "Authorization: Bearer <oidc access token> is required (POST /oidc/token, grant_type=client_credentials)");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            var claims = tokenService.validateAccessToken(token);
            if (!REQUIRED_AUDIENCE.equals(claims.getAudience() == null ? null : claims.getAudience().stream().findFirst().orElse(null))) {
                return unauthorized("invalid_audience", "this token's audience is not '" + REQUIRED_AUDIENCE + "' (minted for another client)");
            }
            return null; // provider-issued, dsh-audience Bearer token — authenticated
        } catch (Exception e) {
            return unauthorized("invalid_token", e.getMessage());
        }
    }

    /** RFC 6750 style 401: challenge header plus a machine-readable reason. */
    private static HttpResponse<?> unauthorized(String error, String description) {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"")
            .body(Map.of("error", error, "error_description", description));
    }
}
