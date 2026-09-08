package io.kestra.oidc.controllers;

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.OidcClientService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;

/**
 * MCP OAuth support — this provider acts as the authorization server for MCP clients
 * (Claude Desktop / mcp-remote / the official MCP SDK), following the two RFCs an MCP
 * client requires:
 *
 * <ul>
 *   <li><b>RFC 8414</b> ({@code GET /.well-known/oauth-authorization-server}): metadata
 *       discovery. The MCP client resolves the authorization server from the MCP server
 *       origin ({@code http://localhost:18080}) and learns the authorize / token /
 *       registration endpoints. All endpoints are advertised with the <b>browser-reachable
 *       externalBaseUrl</b> — an MCP client runs on the host and cannot resolve the internal
 *       {@code kestra:8080} service name (the OIDC discovery document keeps the internal
 *       issuer for Nacos, which resolves it server-side).</li>
 *   <li><b>RFC 7591</b> ({@code POST /oidc/register}): dynamic client registration. MCP
 *       clients register their loopback redirect URI on every run, so no static seed client
 *       can cover them. Registration is restricted to <b>public clients</b>
 *       ({@code token_endpoint_auth_method=none}, empty secret): possession is proven with
 *       PKCE (S256) at the token endpoint, which this provider already enforces for public
 *       clients (see {@link io.kestra.oidc.services.OidcClientService#isPublic}).</li>
 * </ul>
 *
 * <p>
 * Registered clients get a {@code mcp-*} {@code client_id} and the {@code mcp} scope; the
 * resource-server gate is {@link OidcMcpBearerAuthFilter}, which requires a valid provider
 * access token whose audience (client_id) starts with {@code mcp-} or whose {@code scope}
 * contains {@code mcp}. See {@code docs/mcp-oauth.md} for the full design.
 */
@Controller
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcMcpOAuthController {

    /** client_id prefix for dynamically registered MCP clients. */
    static final String MCP_CLIENT_ID_PREFIX = "mcp-";

    /** Scopes an MCP client may request at registration; {@code mcp} is always granted. */
    private static final Set<String> ALLOWED_SCOPES = Set.of("openid", "profile", "email", "mcp");

    private static final List<String> ALLOWED_GRANTS = List.of("authorization_code", "refresh_token");

    private final SecureRandom random = new SecureRandom();

    private final OidcConfiguration configuration;
    private final OidcClientService clientService;

    @Inject
    public OidcMcpOAuthController(OidcConfiguration configuration, OidcClientService clientService) {
        this.configuration = configuration;
        this.clientService = clientService;
    }

    // ------------------------------------------------------------------------
    // GET /.well-known/oauth-authorization-server  (RFC 8414)
    // ------------------------------------------------------------------------

    @Get("/.well-known/oauth-authorization-server")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> authorizationServerMetadata() {
        // MCP 客户端运行在宿主机：所有端点必须用浏览器可达的 externalBaseUrl，
        // 不能是内部服务名（kestra:8080 仅容器网络内可解析）。
        String base = configuration.getExternalBaseUrl();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", base);
        metadata.put("authorization_endpoint", base + "/oidc/authorize");
        metadata.put("token_endpoint", base + "/oidc/token");
        metadata.put("registration_endpoint", base + "/oidc/register");
        metadata.put("revocation_endpoint", base + "/oidc/revoke");
        metadata.put("jwks_uri", base + "/oidc/jwks");
        metadata.put("scopes_supported", new ArrayList<>(ALLOWED_SCOPES));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", ALLOWED_GRANTS);
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        return HttpResponse.ok(metadata);
    }

    // ------------------------------------------------------------------------
    // POST /oidc/register  (RFC 7591)
    // ------------------------------------------------------------------------

    /**
     * Dynamic client registration: creates a public (PKCE) MCP client and returns its
     * {@code client_id}. Open registration is intentional for this deployment — an MCP
     * client must be able to register before it has any credential; production hardening
     * (an initial access token, or restricting the endpoint to the docker network) is a
     * documented follow-up (docs/mcp-oauth.md).
     */
    @Post("/oidc/register")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> register(@Body Map<String, Object> body) {
        // redirect_uris: required, non-empty, loopback or https only.
        List<String> redirectUris = stringList(body.get("redirect_uris"));
        if (redirectUris == null || redirectUris.isEmpty()) {
            return registrationError("invalid_redirect_uri", "redirect_uris is required and must be non-empty");
        }
        for (String uri : redirectUris) {
            if (!isLoopbackOrHttps(uri)) {
                return registrationError("invalid_redirect_uri",
                    "redirect_uri '" + uri + "' must be loopback (http://127.0.0.1[:port]/... or http://localhost[:port]/...) or https");
            }
        }

        // grant_types: must contain authorization_code; only authorization_code/refresh_token.
        List<String> grantTypes = stringList(body.get("grant_types"));
        if (grantTypes == null || grantTypes.isEmpty()) {
            grantTypes = List.of("authorization_code", "refresh_token");
        }
        if (!grantTypes.contains("authorization_code")) {
            return registrationError("invalid_grant_type", "grant_types must include 'authorization_code'");
        }
        for (String grant : grantTypes) {
            if (!ALLOWED_GRANTS.contains(grant)) {
                return registrationError("invalid_grant_type", "unsupported grant type '" + grant + "'");
            }
        }

        // token_endpoint_auth_method: only public clients (PKCE), like the seeded dsh-ui/dsh-pc.
        String authMethod = body.get("token_endpoint_auth_method") == null
            ? "none" : body.get("token_endpoint_auth_method").toString();
        if (!"none".equals(authMethod)) {
            return registrationError("invalid_client_metadata",
                "only public clients are supported: token_endpoint_auth_method must be 'none' (PKCE S256)");
        }

        // response_types: ['code'] only.
        List<String> responseTypes = stringList(body.get("response_types"));
        if (responseTypes != null && !(responseTypes.size() == 1 && "code".equals(responseTypes.get(0)))) {
            return registrationError("invalid_client_metadata", "response_types must be ['code']");
        }

        // scope: subset of the supported set; 'mcp' is always granted so the resource
        // server gate (OidcMcpBearerAuthFilter) can rely on it.
        final List<String> scopes;
        try {
            scopes = parseScopes(body.get("scope"));
        } catch (RegistrationRejected rejected) {
            return registrationError(rejected.error, rejected.description);
        }

        String clientId = MCP_CLIENT_ID_PREFIX + randomHex(8);
        clientService.create(clientId, "", redirectUris, grantTypes, scopes, "dsh", List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", System.currentTimeMillis() / 1000);
        if (body.get("client_name") != null && !body.get("client_name").toString().isBlank()) {
            response.put("client_name", body.get("client_name").toString());
        }
        response.put("redirect_uris", redirectUris);
        response.put("grant_types", grantTypes);
        response.put("response_types", List.of("code"));
        response.put("token_endpoint_auth_method", "none");
        response.put("scope", String.join(" ", scopes));
        // Public client: no client_secret is issued (RFC 7591 §2.3.2).
        return HttpResponse.status(HttpStatus.CREATED).body(response);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static boolean isLoopbackOrHttps(String uri) {
        final URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if ("https".equals(parsed.getScheme())) {
            return true;
        }
        if (!"http".equals(parsed.getScheme())) {
            return false;
        }
        String host = parsed.getHost();
        return host != null && ("localhost".equalsIgnoreCase(host)
            || host.startsWith("127.")
            || "::1".equals(host)
            || "[::1]".equals(host));
    }

    private static List<String> parseScopes(Object raw) {
        List<String> requested = new ArrayList<>();
        if (raw != null && !raw.toString().isBlank()) {
            for (String scope : raw.toString().split("\\s+")) {
                if (!ALLOWED_SCOPES.contains(scope)) {
                    throw new RegistrationRejected("invalid_scope", "unsupported scope '" + scope + "'");
                }
                if (!requested.contains(scope)) {
                    requested.add(scope);
                }
            }
        }
        if (!requested.contains("mcp")) {
            requested.add("mcp");
        }
        // 注册请求未声明 scope 时授予全套支持范围：MCP 客户端发现元数据后通常请求
        // scopes_supported 全量（"openid profile email mcp"），授权端点会按此校验。
        if (requested.size() == 1) {
            requested.addAll(List.of("openid", "profile", "email"));
        }
        return requested;
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static HttpResponse<?> registrationError(String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return HttpResponse.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Thrown to unwind invalid scope requests; caught below and mapped to a 400. */
    private static final class RegistrationRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String error;
        private final String description;

        private RegistrationRejected(String error, String description) {
            this.error = error;
            this.description = description;
        }
    }
}
