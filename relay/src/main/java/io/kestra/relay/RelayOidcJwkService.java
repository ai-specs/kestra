package io.kestra.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;

import com.nimbusds.jose.jwk.RSAKey;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Read-only view of the Kestra OIDC provider signing keys ({@code oidc_jwk}): the relay verifies
 * RS256 access-token signatures against the ACTIVE public key stored by the IdP. Unlike the
 * oidc-provider module's OidcJwkService, this service NEVER generates or stores keys — the relay
 * is a validating consumer only.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class RelayOidcJwkService {

    private final DataSource dataSource;

    @Inject
    public RelayOidcJwkService(DataSource dataSource) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context
        // (mirrors oidc-provider's OidcJwkService).
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
    }

    /**
     * The currently active RSA signing key (public part), or an empty optional when the IdP has
     * not seeded one yet — in which case every token validation fails (the provider is not
     * issuing yet).
     */
    public Optional<RSAKey> activeSigningKey() {
        final String sql = "SELECT kid, jwk, algorithm FROM oidc_jwk WHERE active = TRUE ORDER BY created_at LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(RSAKey.parse(rs.getString("jwk")));
        } catch (SQLException e) {
            throw new RelayOidcException("cannot read active OIDC signing key", e);
        } catch (java.text.ParseException e) {
            throw new RelayOidcException("stored active OIDC signing key is malformed", e);
        }
    }
}
