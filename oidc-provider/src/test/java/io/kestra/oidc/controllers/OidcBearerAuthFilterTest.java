package io.kestra.oidc.controllers;

import java.util.List;

import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;

import io.kestra.oidc.OidcPostgresTestBase;
import io.kestra.oidc.services.OidcTokenService;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * The Bearer guard on /api/v1/dsh/**: only access tokens issued by this provider pass
 * (RS256 + expiry + revocation via {@link OidcTokenService#validateAccessToken}).
 *
 * <p>
 * Adapted to the current {@link OidcBearerAuthFilter} surface: {@code doFilter(request, chain)}
 * returning a {@link Publisher}; the chain is stubbed to yield a 200 so a pass-through is
 * distinguishable from a guard interception.
 */
class OidcBearerAuthFilterTest extends OidcPostgresTestBase {

    private static OidcBearerAuthFilter filter;
    private static String accessToken;

    /** A chain that simply proceeds with a 200 — used to observe pass-through vs interception. */
    private static final ServerFilterChain PROCEEDING_CHAIN =
        request -> io.micronaut.core.async.publisher.Publishers.just(HttpResponse.ok());

    @BeforeAll
    static void issueToken() {
        filter = new OidcBearerAuthFilter(tokenService, configuration);
        accessToken = tokenService.issueAccessToken(
            new ClientID("dsh"), "admin@kestra.io", "admin@kestra.io", "admin@kestra.io",
            List.of("admin"), new Scope("openid")).getValue();
    }

    private static MutableHttpResponse<?> run(HttpRequest<?> request) {
        return Flux.from(filter.doFilter(request, PROCEEDING_CHAIN)).blockFirst();
    }

    @Test
    void providerIssuedBearerTokenPasses() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/sessions")
            .header("Authorization", "Bearer " + accessToken);
        // Pass-through: the response is the chain's 200, not a guard 401.
        assertThat(run(request).getStatus().getCode(), is(200));
    }

    @Test
    void tokenMintedForAnotherClientIsRejectedByAudience() {
        // nacos 客户端的 token（aud=nacos）不得访问 dsh 生态 API——取代旧静态网关 token 的隔离作用
        String nacosToken = tokenService.issueAccessToken(
            new ClientID("nacos"), "nacos", "nacos", "nacos",
            List.of("admin"), new Scope("openid")).getValue();
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/gateway/enterprise/crm/query")
            .header("Authorization", "Bearer " + nacosToken);
        MutableHttpResponse<?> response = run(request);
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), startsWith("Bearer error=\"invalid_audience\""));
    }

    @Test
    void missingTokenIsRejectedWithChallenge() {
        MutableHttpResponse<?> response = run(HttpRequest.GET("/api/v1/dsh/sessions"));
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), startsWith("Bearer error=\"missing_token\""));
    }

    @Test
    void forgedTokenIsRejected() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/metrics/summary")
            .header("Authorization", "Bearer " + accessToken.substring(0, accessToken.length() - 8) + "AAAAAAAA");
        MutableHttpResponse<?> response = run(request);
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    void garbageTokenIsRejected() {
        MutableHttpRequest<?> request = HttpRequest.GET("/api/v1/dsh/sessions")
            .header("Authorization", "Bearer not-a-jwt");
        MutableHttpResponse<?> response = run(request);
        assertThat(response.getStatus().getCode(), is(401));
        assertThat(response.header("WWW-Authenticate"), containsString("invalid_token"));
    }
}
