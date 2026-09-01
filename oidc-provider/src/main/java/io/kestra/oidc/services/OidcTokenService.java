package io.kestra.oidc.services;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;

import io.kestra.oidc.OidcConfiguration;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Issues and validates OIDC tokens.
 *
 * <p>
 * <b>Access tokens</b> are RS256-signed JWTs (needed by the Nacos OIDC plugin, which verifies them
 * against the JWKS endpoint and reads {@code iss/sub/aud/roles/exp/iat}) and are stored in
 * {@code oidc_token} for introspection and revocation. <b>Refresh tokens</b> are opaque random
 * strings stored the same way. <b>ID tokens</b> are RS256-signed JWTs per the OIDC core spec.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcTokenService {

    /** A stored token record. */
    public record StoredToken(
        long id,
        String clientId,
        String subject,
        String tokenType,
        String value,
        List<String> scopes,
        Instant issuedAt,
        Instant expiresAt,
        boolean revoked
    ) {}

    /** Result of issuing a token set at the token endpoint. */
    public record IssuedTokens(AccessToken accessToken, RefreshToken refreshToken, SignedJWT idToken) {}

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final OidcConfiguration configuration;
    private final OidcJwkService jwkService;

    @Inject
    public OidcTokenService(
        DataSource dataSource,
        ObjectMapper objectMapper,
        OidcConfiguration configuration,
        OidcJwkService jwkService
    ) {
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context.
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.objectMapper = objectMapper;
        this.configuration = configuration;
        this.jwkService = jwkService;
    }

    /**
     * Issues an access token (RS256 JWT) and persists it.
     *
     * @param clientId the authenticated client
     * @param subject the token subject (user id or client id)
     * @param name display name claim
     * @param email email claim
     * @param roles roles claim
     * @param scope granted scopes
     * @return the issued bearer access token
     */
    public BearerAccessToken issueAccessToken(
        ClientID clientId,
        String subject,
        String name,
        String email,
        List<String> roles,
        Scope scope
    ) {
        Instant now = Instant.now();
        Instant exp = now.plus(configuration.getAccessTokenTtl());
        JWTClaimsSet claims = accessTokenClaims(clientId, subject, name, email, roles, scope, now, exp);
        String jwt = sign(claims).serialize();
        persist("access", clientId.getValue(), subject, scope.toStringList(), jwt, now, exp);
        return new BearerAccessToken(jwt, configuration.getAccessTokenTtl().getSeconds(), scope);
    }

    /** Issues a new opaque refresh token and persists it. */
    public RefreshToken issueRefreshToken(ClientID clientId, String subject, Scope scope) {
        RefreshToken refreshToken = new RefreshToken();
        Instant now = Instant.now();
        Instant exp = now.plus(configuration.getRefreshTokenTtl());
        persist("refresh", clientId.getValue(), subject, scope.toStringList(), refreshToken.getValue(), now, exp);
        return refreshToken;
    }

    /**
     * Issues an OIDC ID token (RS256 JWT).
     *
     * @param clientId the client the ID token is intended for (audience)
     * @param subject the user subject
     * @param name display name
     * @param email email
     * @param roles roles
     * @param nonce optional nonce echo
     * @return the signed ID token
     */
    public SignedJWT issueIdToken(
        ClientID clientId,
        String subject,
        String name,
        String email,
        List<String> roles,
        String nonce
    ) {
        Instant now = Instant.now();
        Instant exp = now.plus(configuration.getAccessTokenTtl());
        IDTokenClaimsSet idTokenClaims = new IDTokenClaimsSet(
            new Issuer(configuration.getIssuer()),
            new Subject(subject),
            List.of(new Audience(clientId.getValue())),
            Date.from(exp),
            Date.from(now)
        );
        idTokenClaims.setClaim("name", name);
        idTokenClaims.setClaim("email", email);
        idTokenClaims.setClaim("roles", roles);
        if (nonce != null) {
            idTokenClaims.setClaim("nonce", nonce);
        }
        try {
            return sign(idTokenClaims.toJWTClaimsSet());
        } catch (com.nimbusds.oauth2.sdk.ParseException e) {
            throw new IllegalStateException("Failed to build ID token claims", e);
        }
    }

    /**
     * Parses and validates an access token: signature, issuer, expiry and revocation state.
     *
     * @param value the raw access token (JWT)
     * @return the validated JWT claims
     */
    public JWTClaimsSet validateAccessToken(String value) {
        SignedJWT signedJWT = parseAndVerify(value);
        JWTClaimsSet claims = claimsOf(signedJWT);
        if (claims.getIssuer() != null && !configuration.getIssuer().equals(claims.getIssuer())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": token issuer mismatch"));
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.before(new Date())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": token expired"));
        }
        StoredToken stored = findByValue(value)
            .orElseThrow(() -> new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": unknown token")));
        if (stored.revoked()) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": token revoked"));
        }
        if (!"access".equals(stored.tokenType())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": not an access token"));
        }
        return claims;
    }

    /** Validates a refresh token for exchange, returning its stored record. */
    public StoredToken validateRefreshToken(String value, String expectedClientId) {
        StoredToken stored = findByValue(value)
            .orElseThrow(() -> new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": unknown refresh token")));
        if (stored.revoked()) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": refresh token revoked"));
        }
        if (stored.expiresAt() != null && stored.expiresAt().isBefore(Instant.now())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": refresh token expired"));
        }
        if (!"refresh".equals(stored.tokenType())) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": not a refresh token"));
        }
        if (!stored.clientId().equals(expectedClientId)) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": refresh token issued to a different client"));
        }
        return stored;
    }

    /** Looks up a stored token by its raw value. */
    public Optional<StoredToken> findByValue(String value) {
        final String sql = """
            SELECT id, client_id, subject, token_type, value, scopes, issued_at, expires_at, revoked
            FROM oidc_token WHERE value = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load OIDC token", e);
        }
    }

    /** Marks a token revoked (RFC 7009). Idempotent for unknown tokens. */
    public void revoke(String value) {
        final String sql = "UPDATE oidc_token SET revoked = TRUE WHERE value = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to revoke OIDC token", e);
        }
    }

    /** Builds access-token claims shared by all grant types. */
    private JWTClaimsSet accessTokenClaims(
        ClientID clientId,
        String subject,
        String name,
        String email,
        List<String> roles,
        Scope scope,
        Instant now,
        Instant exp
    ) {
        return new JWTClaimsSet.Builder()
            .issuer(configuration.getIssuer())
            .subject(subject)
            .audience(clientId.getValue())
            .expirationTime(Date.from(exp))
            .issueTime(Date.from(now))
            .jwtID(java.util.UUID.randomUUID().toString())
            .claim("scope", scope != null && scope.toStringList() != null ? String.join(" ", scope.toStringList()) : "")
            .claim("client_id", clientId.getValue())
            .claim("name", name != null ? name : subject)
            .claim("email", email != null ? email : subject)
            .claim("roles", roles != null ? roles : List.of())
            .build();
    }

    private SignedJWT sign(JWTClaimsSet claims) {
        try {
            RSAKey signingKey = jwkService.activeSigningKey();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();
            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
            return signedJWT;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign OIDC token", e);
        }
    }

    /** Parses a JWT and verifies its RS256 signature against the active public key. */
    private SignedJWT parseAndVerify(String value) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(value);
            RSAKey signingKey = jwkService.activeSigningKey();
            if (!signedJWT.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))) {
                throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": token signature verification failed"));
            }
            return signedJWT;
        } catch (OidcException e) {
            throw e;
        } catch (Exception e) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": invalid token: " + e.getMessage()));
        }
    }

    private JWTClaimsSet claimsOf(SignedJWT signedJWT) {
        try {
            return signedJWT.getJWTClaimsSet();
        } catch (Exception e) {
            throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": cannot parse token claims"));
        }
    }

    private void persist(String tokenType, String clientId, String subject, List<String> scopes, String value, Instant issuedAt, Instant expiresAt) {
        final String sql = """
            INSERT INTO oidc_token (client_id, subject, token_type, value, scopes, issued_at, expires_at, revoked)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, FALSE)""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clientId);
            ps.setString(2, subject);
            ps.setString(3, tokenType);
            ps.setString(4, value);
            ps.setString(5, objectMapper.writeValueAsString(scopes));
            ps.setTimestamp(6, Timestamp.from(issuedAt));
            ps.setTimestamp(7, expiresAt != null ? Timestamp.from(expiresAt) : null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store OIDC token", e);
        }
    }

    private StoredToken map(ResultSet rs) throws SQLException {
        Timestamp issuedAt = rs.getTimestamp("issued_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        return new StoredToken(
            rs.getLong("id"),
            rs.getString("client_id"),
            rs.getString("subject"),
            rs.getString("token_type"),
            rs.getString("value"),
            jsonList(rs.getString("scopes")),
            issuedAt != null ? issuedAt.toInstant() : null,
            expiresAt != null ? expiresAt.toInstant() : null,
            rs.getBoolean("revoked")
        );
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON array in oidc_token: " + json, e);
        }
    }
}
