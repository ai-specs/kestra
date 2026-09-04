package io.kestra.oidc.controllers;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.SubjectType;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;

import io.kestra.oidc.OidcConfiguration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;

/**
 * OpenID Connect Discovery endpoint ({@code GET /.well-known/openid-configuration}).
 *
 * <p>
 * The discovery document is built with the Nimbus {@link OIDCProviderMetadata} class and advertises
 * the issuer, the OIDC/OAuth2 endpoints of this provider, supported scopes, response types, grant
 * types, PKCE methods, token endpoint auth methods and the RS256 ID token signing algorithm.
 */
@Controller("/.well-known")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcDiscoveryController {

    private final OidcConfiguration configuration;

    @Inject
    public OidcDiscoveryController(OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    @Get("/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> openIdConfiguration() {
        String issuer = configuration.getIssuer();
        // Browser-reachable base for the authorization endpoint only: in the docker-compose
        // topology the issuer is the internal service name (http://kestra:8080) that Nacos uses
        // for token/jwks/userinfo, while the browser must be redirected to a host-side address.
        String authorizationBase = configuration.getExternalBaseUrl();

        OIDCProviderMetadata metadata = new OIDCProviderMetadata(
            new Issuer(issuer),
            Collections.singletonList(SubjectType.PUBLIC),
            URI.create(issuer + "/oidc/jwks")
        );
        metadata.setAuthorizationEndpointURI(URI.create(authorizationBase + "/oidc/authorize"));
        metadata.setTokenEndpointURI(URI.create(issuer + "/oidc/token"));
        metadata.setUserInfoEndpointURI(URI.create(issuer + "/oidc/userinfo"));
        metadata.setIntrospectionEndpointURI(URI.create(issuer + "/oidc/introspect"));
        metadata.setRevocationEndpointURI(URI.create(issuer + "/oidc/revoke"));
        // RP-initiated logout 的登出端点：任何依赖 OIDC Discovery 的 RP（Nacos、dsh pc、
        // dsh 手机端）都从这里发现 end_session_endpoint，退出时跳到这里清 oidc_session。
        // 用浏览器可达的 externalBaseUrl（与 authorization_endpoint 一致），不能是内部
        // 服务名 —— 否则 RP 拿到的登出地址在浏览器不可达，RP-initiated logout 会失效。
        metadata.setEndSessionEndpointURI(URI.create(authorizationBase + "/oidc/logout"));
        metadata.setScopes(new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.PROFILE, OIDCScopeValue.EMAIL));
        metadata.setResponseTypes(Collections.singletonList(ResponseType.CODE));
        metadata.setGrantTypes(Arrays.asList(
            GrantType.AUTHORIZATION_CODE,
            GrantType.CLIENT_CREDENTIALS,
            GrantType.REFRESH_TOKEN
        ));
        metadata.setCodeChallengeMethods(Arrays.asList(CodeChallengeMethod.S256, CodeChallengeMethod.PLAIN));
        metadata.setTokenEndpointAuthMethods(Arrays.asList(
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
            ClientAuthenticationMethod.CLIENT_SECRET_POST
        ));
        metadata.setIDTokenJWSAlgs(Collections.singletonList(JWSAlgorithm.RS256));

        return HttpResponse.ok(metadata.toJSONObject());
    }
}
