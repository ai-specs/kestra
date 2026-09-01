package io.kestra.oidc;

import java.util.List;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.token.Tokens;

import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization code flow tests: code creation, single-use consumption, PKCE (S256) verification
 * and full authorization-code token exchange.
 */
class OidcAuthorizationCodeFlowTest extends OidcPostgresTestBase {

    private static final ClientID CLIENT = new ClientID("nacos");
    private static final String REDIRECT = "http://localhost:8848/nacos/v1/auth/oidc/callback";
    private static final String SUBJECT = "admin@kestra.io";
    private static final String NONCE = "nonce-123";

    @Test
    void codeIsCreatedWithPkceChallengeAndNonce() {
        CodeVerifier verifier = new CodeVerifier();
        AuthorizationCode code = authCodeService.create(
            CLIENT, SUBJECT, REDIRECT, List.of("openid", "profile"),
            CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(),
            CodeChallengeMethod.S256,
            NONCE);

        OidcAuthorizationCodeService.StoredCode stored = authCodeService.find(code.getValue()).orElseThrow();
        assertEquals(SUBJECT, stored.subject());
        assertEquals(REDIRECT, stored.redirectUri());
        assertEquals(NONCE, stored.nonce());
        assertNotNull(stored.codeChallenge());
        assertEquals(CodeChallengeMethod.S256, stored.codeChallengeMethod());
    }

    @Test
    void codeConsumptionRequiresMatchingPkceVerifier() {
        CodeVerifier verifier = new CodeVerifier();
        AuthorizationCode code = authCodeService.create(
            CLIENT, SUBJECT, REDIRECT, List.of("openid"),
            CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(),
            CodeChallengeMethod.S256,
            null);

        // Wrong verifier → invalid_grant.
        CodeVerifier wrong = new CodeVerifier();
        assertThrows(OidcException.class,
            () -> authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, wrong));

        // Missing verifier → invalid_grant.
        assertThrows(OidcException.class,
            () -> authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, null));

        // Correct verifier → success.
        OidcAuthorizationCodeService.StoredCode consumed =
            authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, verifier);
        assertTrue(consumed.used());
    }

    @Test
    void codeIsSingleUse() {
        CodeVerifier verifier = new CodeVerifier();
        AuthorizationCode code = authCodeService.create(
            CLIENT, SUBJECT, REDIRECT, List.of("openid"),
            CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(),
            CodeChallengeMethod.S256,
            null);
        authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, verifier);
        assertThrows(OidcException.class,
            () -> authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, verifier));
    }

    @Test
    void codeIsRejectedForWrongClientOrRedirect() {
        CodeVerifier verifier = new CodeVerifier();
        AuthorizationCode code = authCodeService.create(
            CLIENT, SUBJECT, REDIRECT, List.of("openid"),
            CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(),
            CodeChallengeMethod.S256,
            null);
        assertThrows(OidcException.class,
            () -> authCodeService.consume(code.getValue(), "other-client", REDIRECT, verifier));
        assertThrows(OidcException.class,
            () -> authCodeService.consume(code.getValue(), CLIENT.getValue(), "http://evil.example/cb", verifier));
    }

    @Test
    void fullAuthorizationCodeExchange() throws Exception {
        CodeVerifier verifier = new CodeVerifier();
        AuthorizationCode code = authCodeService.create(
            CLIENT, SUBJECT, REDIRECT, List.of("openid", "profile", "email"),
            CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(),
            CodeChallengeMethod.S256,
            NONCE);

        OidcAuthorizationCodeService.StoredCode stored =
            authCodeService.consume(code.getValue(), CLIENT.getValue(), REDIRECT, verifier);
        Scope scope = new Scope(stored.scopes().toArray(new String[0]));
        io.kestra.oidc.services.OidcUserService.OidcUser user = userService.bySubject(stored.subject());

        BearerAccessToken accessToken = tokenService.issueAccessToken(
            CLIENT, user.sub(), user.name(), user.email(), user.roles(), scope);
        RefreshToken refreshToken = tokenService.issueRefreshToken(CLIENT, user.sub(), scope);
        String idToken = tokenService.issueIdToken(
            CLIENT, user.sub(), user.name(), user.email(), user.roles(), stored.nonce()).serialize();

        // Access token validates.
        assertEquals(SUBJECT, tokenService.validateAccessToken(accessToken.getValue()).getSubject());
        // Refresh token is stored and exchangeable.
        assertEquals(SUBJECT, tokenService.validateRefreshToken(refreshToken.getValue(), CLIENT.getValue()).subject());
        // ID token echoes the nonce.
        assertEquals(NONCE, com.nimbusds.jwt.SignedJWT.parse(idToken).getJWTClaimsSet().getStringClaim("nonce"));
    }
}
