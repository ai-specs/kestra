package io.kestra.oidc.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;

import io.kestra.oidc.OidcConfiguration;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Persistence for OIDC authorization codes (table {@code oidc_authorization_code}).
 *
 * <p>
 * Codes are single-use, short-lived and may carry a PKCE {@code code_challenge} that must be
 * proven at the token endpoint ({@code S256} verification).
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcAuthorizationCodeService {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final OidcConfiguration configuration;

    /** A stored authorization code. */
    public record StoredCode(
        String code,
        ClientID clientId,
        String subject,
        String redirectUri,
        List<String> scopes,
        String codeChallenge,
        CodeChallengeMethod codeChallengeMethod,
        String nonce,
        Instant expiresAt,
        boolean used
    ) {}

    @Inject
    public OidcAuthorizationCodeService(DataSource dataSource, ObjectMapper objectMapper, OidcConfiguration configuration) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context.
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.objectMapper = objectMapper;
        this.configuration = configuration;
    }

    /**
     * Generates and persists a new authorization code for the given request.
     *
     * @param clientId the requesting client
     * @param subject the authenticated user subject
     * @param redirectUri the redirect URI validated by the authorize endpoint
     * @param scopes the granted scopes
     * @param codeChallenge optional PKCE challenge (may be {@code null})
     * @param codeChallengeMethod optional PKCE method (may be {@code null})
     * @param nonce optional OIDC nonce (may be {@code null})
     * @return the generated code
     */
    public AuthorizationCode create(
        ClientID clientId,
        String subject,
        String redirectUri,
        List<String> scopes,
        String codeChallenge,
        CodeChallengeMethod codeChallengeMethod,
        String nonce
    ) {
        AuthorizationCode code = new AuthorizationCode();
        Instant expiresAt = Instant.now().plus(configuration.getAuthorizationCodeTtl());
        final String sql = """
            INSERT INTO oidc_authorization_code
                (code, client_id, subject, redirect_uri, scopes, code_challenge, code_challenge_method, nonce, expires_at, used)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, FALSE)""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, code.getValue());
            ps.setString(2, clientId.getValue());
            ps.setString(3, subject);
            ps.setString(4, redirectUri);
            ps.setString(5, objectMapper.writeValueAsString(scopes));
            ps.setString(6, codeChallenge);
            ps.setString(7, codeChallengeMethod != null ? codeChallengeMethod.getValue() : null);
            ps.setString(8, nonce);
            ps.setTimestamp(9, Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store OIDC authorization code", e);
        }
        return code;
    }

    /** Loads a stored code by its value. */
    public Optional<StoredCode> find(String codeValue) {
        final String sql = """
            SELECT code, client_id, subject, redirect_uri, scopes, code_challenge, code_challenge_method, nonce, expires_at, used
            FROM oidc_authorization_code WHERE code = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, codeValue);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new StoredCode(
                        rs.getString("code"),
                        new ClientID(rs.getString("client_id")),
                        rs.getString("subject"),
                        rs.getString("redirect_uri"),
                        jsonList(rs.getString("scopes")),
                        rs.getString("code_challenge"),
                        codeChallengeMethod(rs.getString("code_challenge_method")),
                        rs.getString("nonce"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("used")
                    ));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load OIDC authorization code", e);
        }
    }

    /**
     * Consumes (marks used) a code. Throws the appropriate OAuth2 error when the code is missing,
     * already used or expired.
     *
     * @param codeValue the presented code
     * @param expectedClientId the authenticated client id (must match the stored client)
     * @param redirectUri the redirect URI presented at the token endpoint (must match)
     * @param codeVerifier the PKCE verifier presented at the token endpoint (may be {@code null})
     * @return the stored code, with {@code used} set to {@code true}
     */
    public StoredCode consume(String codeValue, String expectedClientId, String redirectUri, CodeVerifier codeVerifier) {
        StoredCode stored = find(codeValue)
            .orElseThrow(() -> new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": unknown authorization code")));
        if (stored.used()) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": authorization code already used"));
        }
        if (stored.expiresAt().isBefore(Instant.now())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": authorization code expired"));
        }
        if (!stored.clientId().getValue().equals(expectedClientId)) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": authorization code issued to a different client"));
        }
        if (redirectUri != null && !stored.redirectUri().equals(redirectUri)) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": redirect_uri mismatch"));
        }

        // PKCE verification (RFC 7636)
        if (stored.codeChallenge() != null) {
            if (codeVerifier == null) {
                throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": PKCE code_verifier required"));
            }
            CodeChallengeMethod method = stored.codeChallengeMethod() != null ? stored.codeChallengeMethod() : CodeChallengeMethod.S256;
            CodeChallenge computed = CodeChallenge.compute(method, codeVerifier);
            if (!constantTimeEquals(stored.codeChallenge(), computed.getValue())) {
                throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": PKCE verification failed"));
            }
        }

        markUsed(stored.code());
        return new StoredCode(
            stored.code(), stored.clientId(), stored.subject(), stored.redirectUri(),
            stored.scopes(), stored.codeChallenge(), stored.codeChallengeMethod(),
            stored.nonce(), stored.expiresAt(), true
        );
    }

    /** Deletes an already-consumed code. */
    public void delete(String codeValue) {
        final String sql = "DELETE FROM oidc_authorization_code WHERE code = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, codeValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete OIDC authorization code", e);
        }
    }

    private void markUsed(String codeValue) {
        final String sql = "UPDATE oidc_authorization_code SET used = TRUE WHERE code = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, codeValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark OIDC authorization code as used", e);
        }
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON array in oidc_authorization_code: " + json, e);
        }
    }

    private static CodeChallengeMethod codeChallengeMethod(String value) {
        if (value == null) {
            return null;
        }
        return CodeChallengeMethod.parse(value);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
