package io.kestra.relay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.sql.DataSource;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Access-token validation for the relay, self-contained (2026-09-27): RS256 signature against
 * the active public key in {@code oidc_jwk}, issuer + expiry claims, then introspection against
 * {@code oidc_token} (revocation, token type). This is the validating subset of the
 * oidc-provider module's OidcTokenService — the relay must not depend on that module because it
 * would auto-register the IdP's login/discovery/user-admin endpoints on this port.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class RelayOidcTokenService {

    private final DataSource dataSource;
    private final RelayOidcConfiguration configuration;
    private final RelayOidcJwkService jwkService;

    @Inject
    public RelayOidcTokenService(DataSource dataSource,
                                 RelayOidcConfiguration configuration,
                                 RelayOidcJwkService jwkService) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context
        // (mirrors oidc-provider's OidcJwkService).
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.configuration = configuration;
        this.jwkService = jwkService;
    }

    /**
     * Full validation of a provider-issued access token: RS256 signature, issuer, expiry,
     * DB introspection (unknown / revoked / wrong type). Returns the claims on success.
     */
    public JWTClaimsSet validateAccessToken(String value) {
        SignedJWT signedJWT = parseAndVerify(value);
        JWTClaimsSet claims;
        try {
            claims = signedJWT.getJWTClaimsSet();
        } catch (Exception e) {
            throw new RelayOidcException("invalid_grant: cannot parse token claims", e);
        }
        if (claims.getIssuer() != null && !configuration.getIssuer().equals(claims.getIssuer())) {
            throw new RelayOidcException("invalid_grant: token issuer mismatch");
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.before(new Date())) {
            throw new RelayOidcException("invalid_grant: token expired");
        }
        StoredToken stored = findByValue(value)
            .orElseThrow(() -> new RelayOidcException("invalid_grant: unknown token"));
        if (stored.revoked()) {
            throw new RelayOidcException("invalid_grant: token revoked");
        }
        if (!"access".equals(stored.tokenType())) {
            throw new RelayOidcException("invalid_grant: not an access token");
        }
        return claims;
    }

    private SignedJWT parseAndVerify(String value) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(value);
            RSAKey signingKey = jwkService.activeSigningKey()
                .orElseThrow(() -> new RelayOidcException("invalid_grant: no active OIDC signing key"));
            if (!signedJWT.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))) {
                throw new RelayOidcException("invalid_grant: token signature verification failed");
            }
            return signedJWT;
        } catch (RelayOidcException e) {
            throw e;
        } catch (Exception e) {
            throw new RelayOidcException("invalid_grant: invalid token: " + e.getMessage(), e);
        }
    }

    /** Introspection lookup by raw value (only the columns validation needs). */
    private Optional<StoredToken> findByValue(String value) {
        final String sql = """
            SELECT token_type, revoked FROM oidc_token WHERE value = ?
            """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredToken(
                    rs.getString("token_type"),
                    rs.getBoolean("revoked")
                ));
            }
        } catch (SQLException e) {
            throw new RelayOidcException("cannot introspect token", e);
        }
    }

    private record StoredToken(String tokenType, boolean revoked) {}
}
