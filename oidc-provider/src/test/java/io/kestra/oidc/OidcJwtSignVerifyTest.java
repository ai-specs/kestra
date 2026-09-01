package io.kestra.oidc;

import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;

import io.kestra.oidc.services.OidcException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT signing / verification tests: RS256 tokens issued by {@code OidcTokenService} must verify
 * against the public key exposed by the JWKS endpoint, and validation must reject tampered,
 * re-issuer or expired tokens.
 */
class OidcJwtSignVerifyTest extends OidcPostgresTestBase {

    @Test
    void accessTokenJwtIsSignedWithRs256AndVerifiesWithJwks() throws Exception {
        ClientID clientId = new ClientID("nacos");
        String subject = "admin@kestra.io";
        String jwt = tokenService.issueAccessToken(
            clientId, subject, "Admin", "admin@kestra.io", List.of("admin"),
            new Scope("openid", "profile", "email")).getValue();

        SignedJWT signedJWT = SignedJWT.parse(jwt);
        assertEquals(JWSAlgorithm.RS256, signedJWT.getHeader().getAlgorithm());
        assertNotNull(signedJWT.getHeader().getKeyID());

        // Verify with the public JWK exposed at /oidc/jwks.
        RSAKey publicKey = jwkService.publicJwkSet().getKeys().stream()
            .filter(jwk -> jwk.getKeyID().equals(signedJWT.getHeader().getKeyID()))
            .map(jwk -> (RSAKey) jwk)
            .findFirst()
            .orElseThrow(() -> new AssertionError("JWKS does not contain signing kid"));
        assertTrue(signedJWT.verify(new RSASSAVerifier(publicKey.toRSAPublicKey())));

        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
        assertEquals("http://localhost:18080", claims.getIssuer());
        assertEquals(subject, claims.getSubject());
        assertEquals(List.of("nacos"), claims.getAudience());
        assertEquals(List.of("admin"), claims.getStringListClaim("roles"));
        assertNotNull(claims.getExpirationTime());
        assertNotNull(claims.getIssueTime());
    }

    @Test
    void validateAccessTokenAcceptsValidToken() {
        String jwt = tokenService.issueAccessToken(
            new ClientID("nacos"), "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
        JWTClaimsSet claims = tokenService.validateAccessToken(jwt);
        assertEquals("admin@kestra.io", claims.getSubject());
    }

    @Test
    void tamperedTokenIsRejected() {
        String jwt = tokenService.issueAccessToken(
            new ClientID("nacos"), "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
        String tampered = jwt.substring(0, jwt.length() - 4) + "AAAA";
        assertThrows(OidcException.class, () -> tokenService.validateAccessToken(tampered));
    }

    @Test
    void unknownOrRevokedTokenIsRejected() {
        assertThrows(OidcException.class, () -> tokenService.validateAccessToken("not-a-jwt"));

        String jwt = tokenService.issueAccessToken(
            new ClientID("nacos"), "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
        tokenService.revoke(jwt);
        assertThrows(OidcException.class, () -> tokenService.validateAccessToken(jwt));
    }

    @Test
    void idTokenCarriesOidcClaimsAndNonce() throws Exception {
        String idToken = tokenService.issueIdToken(
            new ClientID("kestra-self"), "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), "abc-nonce").serialize();

        SignedJWT signedJWT = SignedJWT.parse(idToken);
        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
        assertEquals("http://localhost:18080", claims.getIssuer());
        assertEquals("admin@kestra.io", claims.getSubject());
        assertEquals(List.of("kestra-self"), claims.getAudience());
        assertEquals("abc-nonce", claims.getStringClaim("nonce"));
        assertTrue(claims.getExpirationTime().after(new Date()));
        assertTrue(signedJWT.verify(new RSASSAVerifier(
            jwkService.publicJwkSet().toPublicJWKSet().getKeys().stream()
                .filter(jwk -> jwk.getKeyID().equals(signedJWT.getHeader().getKeyID()))
                .map(jwk -> (RSAKey) jwk).findFirst().orElseThrow().toRSAPublicKey())));
    }
}
