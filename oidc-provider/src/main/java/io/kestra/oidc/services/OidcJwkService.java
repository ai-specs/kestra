package io.kestra.oidc.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Manages the OIDC Provider signing keys (table {@code oidc_jwk}).
 *
 * <p>
 * The active RSA key is used to sign all tokens (RS256). Its public part is exposed through the
 * JWKS endpoint. If no active key exists (e.g. the table was seeded empty), a fresh RSA-2048 key
 * is generated and persisted.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcJwkService {

    private final DataSource dataSource;

    @Inject
    public OidcJwkService(DataSource dataSource) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context.
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
    }

    /** A stored JWK record. */
    public record StoredJwk(String kid, String jwkJson, String algorithm, boolean active) {}

    /** Loads the active signing key, generating and persisting one if none exists. */
    public RSAKey activeSigningKey() {
        Optional<StoredJwk> stored = findActive();
        RSAKey rsaKey = stored.map(this::parseJwk).orElse(null);
        if (rsaKey == null) {
            rsaKey = generateAndStoreDefault();
        }
        return rsaKey;
    }

    /** Exposes the public JWK Set of all active keys (for the {@code /oidc/jwks} endpoint). */
    public JWKSet publicJwkSet() {
        List<JWK> publicKeys = new ArrayList<>();
        for (StoredJwk stored : findAllActive()) {
            RSAKey key = parseJwk(stored);
            publicKeys.add(key.toPublicJWK());
        }
        return new JWKSet(publicKeys);
    }

    private Optional<StoredJwk> findActive() {
        final String sql = "SELECT kid, jwk, algorithm, active FROM oidc_jwk WHERE active = TRUE ORDER BY created_at LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(map(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load active OIDC JWK", e);
        }
    }

    private List<StoredJwk> findAllActive() {
        final String sql = "SELECT kid, jwk, algorithm, active FROM oidc_jwk WHERE active = TRUE ORDER BY created_at";
        List<StoredJwk> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load active OIDC JWKs", e);
        }
    }

    private StoredJwk map(ResultSet rs) throws SQLException {
        return new StoredJwk(
            rs.getString("kid"),
            rs.getString("jwk"),
            rs.getString("algorithm"),
            rs.getBoolean("active")
        );
    }

    private RSAKey parseJwk(StoredJwk stored) {
        try {
            JWK jwk = JWK.parse(stored.jwkJson());
            return (RSAKey) jwk;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored JWK kid=" + stored.kid(), e);
        }
    }

    private RSAKey generateAndStoreDefault() {
        try {
            RSAKey key = new RSAKeyGenerator(2048)
                .keyID("dsh-oidc-" + Instant.now().toEpochMilli())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .generate();
            final String sql = """
                INSERT INTO oidc_jwk (kid, jwk, algorithm, active)
                VALUES (?, ?, 'RS256', TRUE)
                ON CONFLICT (kid) DO NOTHING""";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, key.getKeyID());
                ps.setString(2, key.toJSONString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to persist generated OIDC JWK", e);
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate default OIDC JWK", e);
        }
    }
}
