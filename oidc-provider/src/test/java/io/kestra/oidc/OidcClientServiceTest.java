package io.kestra.oidc;

import java.util.List;
import java.util.Optional;

import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;

import io.kestra.oidc.services.OidcClientService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client validation unit tests (table {@code oidc_client} seeded by the migration).
 */
class OidcClientServiceTest extends OidcPostgresTestBase {

    @Test
    void seededDefaultClientsExist() {
        assertTrue(clientService.find("nacos").isPresent());
        assertTrue(clientService.find("dsh").isPresent());
        assertTrue(clientService.find("kestra-self").isPresent());
    }

    @Test
    void unknownClientIsNotAuthenticated() {
        assertFalse(clientService.authenticate("unknown", "whatever"));
        assertFalse(clientService.authenticate("nacos", null));
    }

    @Test
    void clientSecretVerification() {
        assertTrue(clientService.authenticate("nacos", "nacos-secret-change-me"));
        assertFalse(clientService.authenticate("nacos", "wrong-secret"));
    }

    @Test
    void grantTypesAndScopesValidatedPerClient() {
        OidcClientService.OidcClient nacos = clientService.require("nacos");

        assertTrue(clientService.isGrantTypeAllowed(nacos, "authorization_code"));
        assertTrue(clientService.isGrantTypeAllowed(nacos, "client_credentials"));
        assertTrue(clientService.isGrantTypeAllowed(nacos, "refresh_token"));
        assertFalse(clientService.isGrantTypeAllowed(nacos, "implicit"));

        assertTrue(clientService.isRedirectUriRegistered(nacos, "http://localhost:18480/v1/auth/oidc/callback"));
        assertFalse(clientService.isRedirectUriRegistered(nacos, "http://evil.example/callback"));

        assertTrue(clientService.isScopeAllowed(nacos, List.of("openid", "profile", "email")));
        assertFalse(clientService.isScopeAllowed(nacos, List.of("admin")));
    }

    @Test
    void requireThrowsForUnknownClient() {
        assertThrows(io.kestra.oidc.services.OidcException.class, () -> clientService.require("does-not-exist"));
    }
}
