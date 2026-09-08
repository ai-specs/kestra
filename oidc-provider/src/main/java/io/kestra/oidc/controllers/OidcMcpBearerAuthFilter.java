package io.kestra.oidc.controllers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nimbusds.jwt.JWTClaimsSet;

import io.kestra.core.mcp.models.McpServer;
import io.kestra.mcp.McpServerCache;
import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.OidcTokenService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.FilterPatternStyle;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import jakarta.inject.Inject;

/**
 * Resource-server gate for the MCP protocol endpoints (the {@code /api/v1/{tenant}/mcp}*
 * namespace — the Streamable HTTP transport, the SSE transport and the tool endpoints share
 * it; the MCP server management API {@code /api/v1/{tenant}/mcp-servers}* is NOT matched and
 * stays behind Micronaut Security).
 *
 * <p>
 * Access policy, mirroring the {@code McpServerAuthenticationFilter} semantics but with the
 * dsh OIDC Provider as the identity source (the OSS {@code BASIC} path is deliberately
 * retired in this fork, see {@code docs/deprecated.md}):
 * <ul>
 *   <li>{@code PUBLIC} servers — no authentication (the server itself is the access gate);</li>
 *   <li>{@code PRIVATE} servers — the request MUST carry {@code Authorization: Bearer
 *       <access token>} issued by this provider's token endpoint for a <b>dynamically
 *       registered MCP client</b>: the audience (client_id) starts with {@code mcp-} or the
 *       {@code scope} claim contains {@code mcp}. Validation reuses
 *       {@link OidcTokenService#validateAccessToken}: RS256 signature against the published
 *       JWK, issuer, expiry, stored-not-revoked and token type.</li>
 * </ul>
 *
 * <p>
 * Runs FIRST (order -1000, before Micronaut's SecurityFilter — same ordering trick as
 * {@link OidcBearerAuthFilter}); the compose {@code intercept-url-map} marks the
 * {@code /api/v1/{tenant}/mcp}* namespace {@code isAnonymous()} so SecurityFilter passes the
 * request through and this filter is the sole gatekeeper. The validated claims are stashed on
 * the request under the same attribute as the dsh filter, so downstream MCP handlers can read
 * them generically.
 */
@Filter(patternStyle = FilterPatternStyle.ANT, value = "/api/v1/*/mcp/**")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcMcpBearerAuthFilter implements HttpServerFilter {

    /** Same attribute name as {@link OidcBearerAuthFilter}: validated token claims on the request. */
    public static final String CLAIMS_ATTRIBUTE = "io.kestra.oidc.claims";

    private static final String BEARER_PREFIX = "Bearer ";

    /** client_id prefix granted by dynamic registration ({@link OidcMcpOAuthController}). */
    private static final String MCP_CLIENT_ID_PREFIX = "mcp-";

    /** Scope every registered MCP client is granted; the scope claim is an alternative gate. */
    private static final String MCP_SCOPE = "mcp";

    /** Runs before Micronaut's SecurityFilter and the generic CORS filter (same as the dsh filter). */
    public static final int ORDER = -1000;

    private final OidcTokenService tokenService;
    private final McpServerCache mcpServerCache;
    private final OidcConfiguration configuration;

    @Inject
    public OidcMcpBearerAuthFilter(OidcTokenService tokenService, McpServerCache mcpServerCache, OidcConfiguration configuration) {
        this.tokenService = tokenService;
        this.mcpServerCache = mcpServerCache;
        this.configuration = configuration;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // MCP 桌面客户端不做 CORS 预检；OPTIONS 原样放行（路由自会 404/处理）。
        if (io.micronaut.http.HttpMethod.OPTIONS == request.getMethod()) {
            return chain.proceed(request);
        }

        // Path shape: /api/v1/{tenant}/mcp/{serverId}/... (tool endpoints live directly under
        // /api/v1/{tenant}/mcp, see McpToolController — the /mcp segment is what matches us).
        String[] parts = request.getPath().split("/");
        if (parts.length < 6 || !"mcp".equals(parts[4])) {
            return chain.proceed(request);
        }
        String tenantId = parts[3];
        String serverId = parts[5];

        Optional<McpServer> serverOpt = mcpServerCache.get(tenantId, serverId);
        // Unknown server: let the handler answer (404). PUBLIC server: the server itself is the gate.
        if (serverOpt.isEmpty() || serverOpt.get().serverType() == McpServer.ServerType.PUBLIC) {
            return chain.proceed(request);
        }

        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null
            || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Publishers.just(unauthorized("missing_token",
                "Authorization: Bearer <oidc access token> is required for a private MCP server"));
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            JWTClaimsSet claims = tokenService.validateAccessToken(token);
            if (!isMcpToken(claims)) {
                return Publishers.just(unauthorized("invalid_audience",
                    "token was not issued for an MCP client (client_id 'mcp-*' or scope 'mcp')"));
            }
            // Same propagation pattern as OidcBearerAuthFilter (docs/oidc-provider.md §9.2):
            // unconditional getAttributes().put, read back with the two-arg getAttribute.
            request.getAttributes().put(CLAIMS_ATTRIBUTE, claims.toJSONObject());
            return chain.proceed(request);
        } catch (Exception e) {
            return Publishers.just(unauthorized("invalid_token", e.getMessage()));
        }
    }
    /** A token is an MCP token when it was minted for a registered MCP client (aud = client_id)
     *  or carries the {@code mcp} scope. */
    private static boolean isMcpToken(JWTClaimsSet claims) {
        List<String> audiences = claims.getAudience();
        if (audiences != null) {
            for (String audience : audiences) {
                if (audience.startsWith(MCP_CLIENT_ID_PREFIX)) {
                    return true;
                }
            }
        }
        try {
            String scope = claims.getStringClaim("scope");
            return scope != null && Arrays.asList(scope.split(" ")).contains(MCP_SCOPE);
        } catch (java.text.ParseException e) {
            return false;
        }
    }

    /** The scope an MCP client is expected to request (RFC 8414 metadata scopes_supported). */
    private static final String MCP_CHALLENGE_SCOPE = "openid profile email mcp";

    /** RFC 6750 + MCP OAuth challenge: the resource_metadata parameter points the MCP client
     *  at the RFC 8414 metadata endpoint of this provider, and scope carries the supported
     *  scope set so the client builds a valid authorization request without needing RFC 9728
     *  protected-resource metadata (which this deployment does not serve). */
    private MutableHttpResponse<?> unauthorized(String error, String description) {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"" + error + "\", scope=\"" + MCP_CHALLENGE_SCOPE + "\", resource_metadata=\""
                    + configuration.getExternalBaseUrl() + "/.well-known/oauth-authorization-server\"")
            .body(Map.of("error", error, "error_description", description));
    }
}
