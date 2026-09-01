package io.kestra.oidc;

import java.util.List;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Client credentials flow tests: a service principal (subject = client id) receives an RS256
 * access token carrying its roles, usable against the protected APIs.
 */
class OidcClientCredentialsFlowTest extends OidcPostgresTestBase {

    @Test
    void clientCredentialsIssuesAccessTokenForServicePrincipal() throws Exception {
        ClientID clientId = new ClientID("nacos");
        io.kestra.oidc.services.OidcUserService.OidcUser user = userService.bySubject(clientId.getValue());
        Scope scope = new Scope("openid", "profile");

        String jwt = tokenService.issueAccessToken(
            clientId, user.sub(), user.name(), user.email(), user.roles(), scope).getValue();

        JWTClaimsSet claims = tokenService.validateAccessToken(jwt);
        assertEquals("nacos", claims.getSubject());
        assertEquals(List.of("nacos"), claims.getAudience());
        assertEquals(List.of("admin"), claims.getStringListClaim("roles"));
        assertNotNull(claims.getExpirationTime());
    }
}
