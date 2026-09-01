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
import io.micronaut.http.filter.ServerFilterPhase;

import jakarta.inject.Inject;

/**
 * Guards the dsh ecosystem APIs ({@code /api/v1/dsh/**}) with a token issued by this OIDC
 * Provider — the same IdP that authenticates the Kestra UI and Nacos SSO.
 *
 * <p>
 * Callers (dsh (PC) plugins, scripts, dsh-ui backends) obtain an access token from
 * {@code POST /oidc/token} — the machine-to-machine path is the {@code client_credentials}
 * grant for the seeded {@code dsh} client — and present it as {@code Authorization: Bearer …}.
 * Validation is the provider's own {@link OidcTokenService#validateAccessToken}: RS256 signature
 * against the published JWK, issuer, expiry, revocation and token type.
 *
 * <p>
 * The path stays {@code isAnonymous()} in the Micronaut {@code intercept-url-map} (Kestra session
 * cookies are a browser concern); this filter is the authoritative check. The gateway endpoints
 * additionally keep their {@code X-Dsh-Gateway-Token} — two independent factors, as before.
 */
@ServerFilter(patternStyle = FilterPatternStyle.ANT, value = "/api/v1/dsh/**")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcBearerAuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final OidcTokenService tokenService;

    @Inject
    public OidcBearerAuthFilter(OidcTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(@NonNull HttpRequest<?> request) {
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            try {
                tokenService.validateAccessToken(token);
                return null; // authenticated — proceed
            } catch (Exception e) {
                return unauthorized("invalid_token", e.getMessage());
            }
        }
        return unauthorized("missing_token", "Authorization: Bearer <oidc access token> is required on /api/v1/dsh/** (POST /oidc/token, grant_type=client_credentials)");
    }

    /** RFC 6750 style 401: challenge header plus a machine-readable reason. */
    private static HttpResponse<?> unauthorized(String error, String description) {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"")
            .body(Map.of("error", error, "error_description", description));
    }
}
