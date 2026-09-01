package io.kestra.oidc.controllers;

import java.util.List;

import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;

import io.kestra.oidc.OidcPostgresTestBase;
import io.kestra.oidc.services.OidcTokenService;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * The Bearer guard on /api/v1/dsh/**: only access tokens issued by this provider pass
 * (RS256 + expiry + revocation via {@link OidcTokenService#validateAccessToken}).
 */
class OidcBearerAuthFilterTest extends OidcPostgresTestBase {

    private static OidcBearerAuthFilter filter;
    private static String accessToken;

    @BeforeAll
    static void issueToken() {
        filter = new OidcBearerAuthFilter(tokenService);
        accessToken = tokenService.issueAccessToken(
            new ClientID("dsh"), "admin@kestra.io", "admin@kestra.io", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
    }

    @Test
    void providerIssuedBearerTokenPasses() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/sessions")
            .header("Authorization", "Bearer " + accessToken);
        assertThat(filter.filter(request), nullValue());
    }

    @Test
    void missingTokenIsRejectedWithChallenge() {
        HttpResponse<?> response = filter.filter(HttpRequest.GET("/api/v1/dsh/sessions"));
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), startsWith("Bearer error=\"missing_token\""));
    }

    @Test
    void forgedTokenIsRejected() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/metrics/summary")
            .header("Authorization", "Bearer " + accessToken.substring(0, accessToken.length() - 8) + "AAAAAAAA");
        HttpResponse<?> response = filter.filter(request);
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    void garbageTokenIsRejected() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/sessions")
            .header("Authorization", "Bearer not-a-jwt");
        HttpResponse<?> response = filter.filter(request);
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), containsString("invalid_token"));
    }
}
