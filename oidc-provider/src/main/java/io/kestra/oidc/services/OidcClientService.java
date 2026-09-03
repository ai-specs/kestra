package io.kestra.oidc.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.id.ClientID;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Persistence and validation for OAuth2/OIDC clients (table {@code oidc_client}).
 *
 * <p>
 * Grant types are validated against the client record so that e.g. {@code client_credentials}
 * can be disabled per client.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcClientService {

    /** A client as stored in {@code oidc_client}. */
    public record OidcClient(
        ClientID clientId,
        String clientSecret,
        List<String> redirectUris,
        List<String> grantTypes,
        List<String> scopes
    ) {}

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public OidcClientService(DataSource dataSource, ObjectMapper objectMapper) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context.
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.objectMapper = objectMapper;
    }

    /** Finds a client by id. */
    public Optional<OidcClient> find(String clientId) {
        final String sql = """
            SELECT client_id, client_secret, redirect_uris, grant_types, scopes
            FROM oidc_client WHERE client_id = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load OIDC client '" + clientId + "'", e);
        }
    }

    /** Returns the client or throws {@code invalid_client}. */
    public OidcClient require(String clientId) {
        return find(clientId).orElseThrow(() -> new OidcException(OAuth2Error.INVALID_CLIENT));
    }

    /**
     * Verifies {@code client_secret} (constant-time) against the stored value.
     *
     * @param clientId the client identifier
     * @param clientSecret the presented secret, or {@code null}
     * @return {@code true} when the client exists and the secret matches
     */
    public boolean authenticate(String clientId, String clientSecret) {
        Optional<OidcClient> client = find(clientId);
        if (client.isEmpty() || clientSecret == null) {
            return false;
        }
        return constantTimeEquals(client.get().clientSecret(), clientSecret);
    }

    /**
     * A public client ({@code token_endpoint_auth_method=none}) is stored with an EMPTY secret:
     * it cannot keep credentials safe (browser / mobile app / user PC), so it authenticates with
     * PKCE (S256) instead — dsh-ui (mobile) and dsh-pc (user PC) are seeded this way. Confidential
     * clients (nacos, dsh service, kestra-self) keep a real secret and use
     * {@code client_secret_basic}/{@code client_secret_post}.
     */
    public boolean isPublic(OidcClient client) {
        return client.clientSecret() == null || client.clientSecret().isBlank();
    }

    /** Checks that the client may use the given grant type. */
    public boolean isGrantTypeAllowed(OidcClient client, String grantType) {
        return client.grantTypes().contains(grantType);
    }

    /** Checks that the redirect URI is registered for the client. */
    public boolean isRedirectUriRegistered(OidcClient client, String redirectUri) {
        return client.redirectUris().contains(redirectUri);
    }

    /** Checks that the requested scopes are a subset of the scopes granted to the client. */
    public boolean isScopeAllowed(OidcClient client, List<String> requestedScopes) {
        for (String scope : requestedScopes) {
            if (!client.scopes().contains(scope)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Write operations (machine identity management)
    // ------------------------------------------------------------------

    /** Inserts a new client. Used when creating a machine (service account) identity. */
    public void create(
        String clientId,
        String clientSecret,
        List<String> redirectUris,
        List<String> grantTypes,
        List<String> scopes
    ) {
        final String sql = """
            INSERT INTO oidc_client (client_id, client_secret, redirect_uris, grant_types, scopes)
            VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb)""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientId);
            ps.setString(2, clientSecret);
            ps.setString(3, objectMapper.writeValueAsString(
                redirectUris == null ? List.of() : redirectUris));
            ps.setString(4, objectMapper.writeValueAsString(
                grantTypes == null ? List.of("client_credentials") : grantTypes));
            ps.setString(5, objectMapper.writeValueAsString(
                scopes == null ? List.of("openid", "profile", "email") : scopes));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("failed to create OIDC client '" + clientId + "': " + e.getMessage(), e);
        }
    }

    /** Rotates the secret of an existing client (machine credential refresh). */
    public void updateSecret(String clientId, String newSecret) {
        final String sql = "UPDATE oidc_client SET client_secret = ? WHERE client_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newSecret);
            ps.setString(2, clientId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("client '" + clientId + "' does not exist");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to update secret for client '" + clientId + "': " + e.getMessage(), e);
        }
    }

    /** Removes a client record (used when deleting a machine identity). Dependent tokens and
     *  authorization codes are removed first — they reference {@code oidc_client.client_id}
     *  with a plain (non-cascading) foreign key. */
    public void delete(String clientId) {
        final String deleteTokens = "DELETE FROM oidc_token WHERE client_id = ?";
        final String deleteCodes = "DELETE FROM oidc_authorization_code WHERE client_id = ?";
        final String deleteClient = "DELETE FROM oidc_client WHERE client_id = ?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(deleteTokens)) {
                ps.setString(1, clientId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(deleteCodes)) {
                ps.setString(1, clientId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(deleteClient)) {
                ps.setString(1, clientId);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException("failed to delete OIDC client '" + clientId + "': " + e.getMessage(), e);
        }
    }

    private OidcClient map(ResultSet rs) throws SQLException {
        return new OidcClient(
            new ClientID(rs.getString("client_id")),
            rs.getString("client_secret"),
            jsonList(rs.getString("redirect_uris")),
            jsonList(rs.getString("grant_types")),
            jsonList(rs.getString("scopes"))
        );
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON array in oidc_client: " + json, e);
        }
    }

    /** Constant-time string comparison. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aa = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(aa, bb);
    }
}
