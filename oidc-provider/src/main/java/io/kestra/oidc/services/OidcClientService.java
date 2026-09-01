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
