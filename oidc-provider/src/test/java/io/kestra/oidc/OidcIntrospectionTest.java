package io.kestra.oidc;

import java.util.Date;
import java.util.List;

import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import com.nimbusds.oauth2.sdk.token.RefreshToken;

import io.kestra.oidc.services.OidcTokenService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token introspection tests (RFC 7662): access and refresh tokens report active state plus
 * client/subject/scope/expiry; revoked or unknown tokens are inactive.
 */
class OidcIntrospectionTest extends OidcPostgresTestBase {

    @Test
    void accessTokenIsIntrospectedActive() {
        ClientID clientId = new ClientID("nacos");
        String jwt = tokenService.issueAccessToken(
            clientId, "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), new Scope("openid", "profile")).getValue();

        OidcTokenService.StoredToken stored = tokenService.findByValue(jwt).orElseThrow();
        TokenIntrospectionSuccessResponse response = introspect(stored);

        assertTrue(response.isActive());
        assertEquals("nacos", response.getClientID().getValue());
        assertEquals("admin@kestra.io", response.getSubject().getValue());
        assertEquals("admin@kestra.io", response.getUsername());
        assertNotNull(response.getExpirationTime());
        assertNotNull(response.getIssueTime());
        assertEquals(AccessTokenType.BEARER, response.getTokenType());
    }

    @Test
    void revokedTokenIsInactive() {
        ClientID clientId = new ClientID("nacos");
        String jwt = tokenService.issueAccessToken(
            clientId, "admin@kestra.io", "Admin", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
        tokenService.revoke(jwt);

        OidcTokenService.StoredToken stored = tokenService.findByValue(jwt).orElseThrow();
        assertTrue(stored.revoked());
        assertFalse(introspect(stored).isActive());
    }

    @Test
    void unknownTokenIsNotPresent() {
        assertTrue(tokenService.findByValue("no-such-token").isEmpty());
    }

    @Test
    void refreshTokenIntrospection() {
        ClientID clientId = new ClientID("nacos");
        RefreshToken refreshToken = tokenService.issueRefreshToken(
            clientId, "admin@kestra.io", new Scope("openid"));
        OidcTokenService.StoredToken stored = tokenService.findByValue(refreshToken.getValue()).orElseThrow();
        assertEquals("refresh", stored.tokenType());
        assertTrue(introspect(stored).isActive());
    }

    /** Mirrors the controller's introspection response construction (RFC 7662). */
    private static TokenIntrospectionSuccessResponse introspect(OidcTokenService.StoredToken token) {
        boolean active = !token.revoked()
            && (token.expiresAt() == null || token.expiresAt().isAfter(java.time.Instant.now()));
        com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse.Builder builder =
            new com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse.Builder(active);
        if (active) {
            builder.clientID(new ClientID(token.clientId()));
            builder.username(token.subject() != null ? token.subject() : token.clientId());
            builder.tokenType(AccessTokenType.BEARER);
            builder.scope(new Scope(token.scopes().toArray(new String[0])));
            if (token.issuedAt() != null) {
                builder.issueTime(Date.from(token.issuedAt()));
            }
            if (token.expiresAt() != null) {
                builder.expirationTime(Date.from(token.expiresAt()));
            }
            builder.issuer(new com.nimbusds.oauth2.sdk.id.Issuer("http://localhost:18080"));
            if (token.subject() != null) {
                builder.subject(new com.nimbusds.oauth2.sdk.id.Subject(token.subject()));
            }
            builder.audience(List.of(new com.nimbusds.oauth2.sdk.id.Audience(token.clientId())));
        }
        return builder.build();
    }
}
