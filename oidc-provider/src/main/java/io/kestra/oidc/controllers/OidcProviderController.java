package io.kestra.oidc.controllers;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.AuthorizationSuccessResponse;
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.ResponseMode;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenIntrospectionRequest;
import com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenRevocationRequest;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.NimbusHttpAdapter;
import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcClientService;
import io.kestra.oidc.services.OidcException;
import io.kestra.oidc.services.OidcJwkService;
import io.kestra.oidc.services.OidcSessionService;
import io.kestra.oidc.services.OidcTokenService;
import io.kestra.oidc.services.OidcUserService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;

/**
 * OIDC/OAuth2 Provider endpoints ({@code /oidc/*}) implemented with the Nimbus OAuth 2.0 / OIDC
 * SDK (11.38.2): every request is parsed and every response is built with the matching Nimbus
 * class (see {@code docs/oidc-provider-research.md}).
 *
 * <p>
 * Endpoints: {@code /oidc/jwks}, {@code /oidc/authorize}, {@code /oidc/token},
 * {@code /oidc/userinfo}, {@code /oidc/introspect}, {@code /oidc/revoke}. The discovery document
 * is served by {@link OidcDiscoveryController} at {@code /.well-known/openid-configuration}.
 */
@Controller("/oidc")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcProviderController {

    private final OidcConfiguration configuration;
    private final OidcClientService clientService;
    private final OidcAuthorizationCodeService authCodeService;
    private final OidcTokenService tokenService;
    private final OidcJwkService jwkService;
    private final OidcUserService userService;
    private final OidcSessionService sessionService;

    @Inject
    public OidcProviderController(
        OidcConfiguration configuration,
        OidcClientService clientService,
        OidcAuthorizationCodeService authCodeService,
        OidcTokenService tokenService,
        OidcJwkService jwkService,
        OidcUserService userService,
        OidcSessionService sessionService
    ) {
        this.configuration = configuration;
        this.clientService = clientService;
        this.authCodeService = authCodeService;
        this.tokenService = tokenService;
        this.jwkService = jwkService;
        this.userService = userService;
        this.sessionService = sessionService;
    }

    // ------------------------------------------------------------------------
    // GET /oidc/jwks
    // ------------------------------------------------------------------------

    @Get("/jwks")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> jwks() {
        return HttpResponse.ok(jwkService.publicJwkSet().toJSONObject());
    }

    // ------------------------------------------------------------------------
    // GET /oidc/authorize
    // ------------------------------------------------------------------------

    /**
     * Authorization endpoint (authorization code flow, PKCE-aware).
     *
     * <p>
     * Unauthenticated users are redirected to the Kestra login page (with a {@code from} query
     * parameter pointing back to this request). Authenticated users receive a single-use
     * authorization code redirected to the client's registered redirect URI.
     */
    @Get("/authorize")
    public HttpResponse<?> authorize(HttpRequest<?> request) {
        final AuthenticationRequest authRequest;
        try {
            authRequest = AuthenticationRequest.parse(paramsMap(request));
        } catch (ParseException e) {
            return HttpResponse.badRequest("Invalid authorization request: " + e.getMessage());
        }

        ClientID clientId = authRequest.getClientID();
        URI redirectUri = authRequest.getRedirectionURI();
        State state = authRequest.getState();

        Optional<OidcClientService.OidcClient> clientOpt = clientId == null
            ? Optional.empty()
            : clientService.find(clientId.getValue());
        if (clientOpt.isEmpty()) {
            return HttpResponse.badRequest("Unknown client_id");
        }
        OidcClientService.OidcClient client = clientOpt.get();

        if (redirectUri == null || !clientService.isRedirectUriRegistered(client, redirectUri.toString())) {
            return HttpResponse.badRequest("redirect_uri is not registered for this client");
        }

        // Only the plain authorization code response type is supported.
        ResponseType responseType = authRequest.getResponseType();
        if (responseType == null || !responseType.contains(ResponseType.Value.CODE)) {
            return authorizeError(redirectUri, state, OAuth2Error.UNSUPPORTED_RESPONSE_TYPE);
        }

        // Scope validation against the client's granted scopes.
        List<String> requestedScopes = authRequest.getScope() != null
            ? authRequest.getScope().toStringList()
            : client.scopes();
        if (!clientService.isScopeAllowed(client, requestedScopes)) {
            return authorizeError(redirectUri, state, OAuth2Error.INVALID_SCOPE);
        }

        // Public clients (no client_secret — dsh-ui mobile / dsh-pc) MUST use PKCE with S256:
        // the code verifier is their only proof of possession at the token endpoint (RFC 7636,
        // OAuth 2.1). Confidential clients may still send the challenge, but it stays optional.
        CodeChallenge codeChallenge = authRequest.getCodeChallenge();
        CodeChallengeMethod codeChallengeMethod = authRequest.getCodeChallengeMethod();
        if (clientService.isPublic(client)
            && (codeChallenge == null || codeChallengeMethod != CodeChallengeMethod.S256)) {
            return authorizeError(redirectUri, state, OAuth2Error.INVALID_REQUEST
                .appendDescription(": public clients must use PKCE (code_challenge_method=S256)"));
        }

        Optional<OidcUserService.OidcUser> user = userService.authenticatedUser(request);
        if (user.isEmpty()) {
            return redirectToLogin(request);
        }

        AuthorizationCode code = authCodeService.create(
            client.clientId(),
            user.get().sub(),
            redirectUri.toString(),
            requestedScopes,
            codeChallenge != null ? codeChallenge.getValue() : null,
            codeChallengeMethod,
            authRequest.getNonce() != null ? authRequest.getNonce().getValue() : null
        );

        AuthorizationSuccessResponse success = new AuthorizationSuccessResponse(
            redirectUri, code, null, state, ResponseMode.QUERY
        );
        // One-shot sessions (remember_session unchecked) are consumed by this single authorize:
        // revoke them server-side right after issuing the code — the next SSO must re-authenticate.
        Optional<String> sessionId = sessionService.sessionIdFrom(request);
        sessionId.ifPresent(id -> {
            if (sessionService.isOneShot(id)) {
                sessionService.revoke(id);
            }
        });
        return HttpResponse.redirect(success.toURI());
    }

    // ------------------------------------------------------------------------
    // POST /oidc/token
    // ------------------------------------------------------------------------

    /**
     * Token endpoint: supports {@code authorization_code} (with PKCE), {@code client_credentials}
     * and {@code refresh_token} grants. Client authentication via {@code client_secret_basic} or
     * {@code client_secret_post}.
     */
    @Post("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> token(HttpRequest<?> request, @Body Map<String, String> form) {
        try {
            HTTPRequest nimbus = NimbusHttpAdapter.toNimbusFormPost(request, form, ContentType.APPLICATION_URLENCODED);
            TokenRequest tokenRequest = TokenRequest.parse(nimbus);

            ClientID clientId = authenticateClient(tokenRequest, form);
            OidcClientService.OidcClient client = clientService.require(clientId.getValue());

            AuthorizationGrant grant = tokenRequest.getAuthorizationGrant();
            String grantType = grant.getType().getValue();
            if (!clientService.isGrantTypeAllowed(client, grantType)) {
                throw new OidcException(OAuth2Error.UNAUTHORIZED_CLIENT.appendDescription(": grant type not enabled for this client"));
            }

            // Public clients prove possession with PKCE, not a secret: the authorization code was
            // issued with their S256 challenge, so the verifier is mandatory here.
            if (clientService.isPublic(client)
                && grant instanceof AuthorizationCodeGrant publicGrant
                && publicGrant.getCodeVerifier() == null) {
                throw new OidcException(OAuth2Error.INVALID_GRANT.appendDescription(": PKCE code_verifier is required for public clients"));
            }

            if (grant instanceof AuthorizationCodeGrant authorizationCodeGrant) {
                return authorizationCodeToken(client, tokenRequest, authorizationCodeGrant);
            } else if (grant instanceof ClientCredentialsGrant) {
                return clientCredentialsToken(client, tokenRequest);
            } else if (grant instanceof RefreshTokenGrant refreshTokenGrant) {
                return refreshTokenToken(client, tokenRequest, refreshTokenGrant);
            } else {
                throw new OidcException(OAuth2Error.UNSUPPORTED_GRANT_TYPE);
            }
        } catch (ParseException e) {
            return tokenErrorResponse(new ErrorObject(
                "invalid_request", e.getMessage(), 400));
        } catch (OidcException e) {
            return tokenErrorResponse(e.getError());
        }
    }

    private HttpResponse<?> authorizationCodeToken(
        OidcClientService.OidcClient client,
        TokenRequest tokenRequest,
        AuthorizationCodeGrant grant
    ) {
        URI redirectUri = grant.getRedirectionURI();
        OidcAuthorizationCodeService.StoredCode stored = authCodeService.consume(
            grant.getAuthorizationCode().getValue(),
            client.clientId().getValue(),
            redirectUri != null ? redirectUri.toString() : null,
            grant.getCodeVerifier()
        );

        List<String> scopes = stored.scopes();
        if (tokenRequest.getScope() != null) {
            List<String> requested = tokenRequest.getScope().toStringList();
            if (!clientService.isScopeAllowed(client, requested)) {
                throw new OidcException(OAuth2Error.INVALID_SCOPE);
            }
            scopes = requested;
        }
        Scope scope = new Scope(scopes.toArray(new String[0]));

        OidcUserService.OidcUser user = userService.bySubject(stored.subject(), client.projectId());
        BearerAccessToken accessToken = tokenService.issueAccessToken(
            client.clientId(), user.sub(), user.name(), user.email(), user.roles(), scope);
        RefreshToken refreshToken = tokenService.issueRefreshToken(client.clientId(), user.sub(), scope);

        if (scope.contains("openid")) {
            SignedJWT idToken = tokenService.issueIdToken(
                client.clientId(), user.sub(), user.name(), user.email(), user.roles(), stored.nonce());
            OIDCTokenResponse response = new OIDCTokenResponse(new OIDCTokens(idToken, accessToken, refreshToken));
            return HttpResponse.ok(toMap(response.toJSONObject()));
        }
        AccessTokenResponse response = new AccessTokenResponse(new com.nimbusds.oauth2.sdk.token.Tokens(
            accessToken, refreshToken));
        return HttpResponse.ok(toMap(response.toJSONObject()));
    }

    private HttpResponse<?> clientCredentialsToken(
        OidcClientService.OidcClient client,
        TokenRequest tokenRequest
    ) {
        Scope scope = tokenRequest.getScope() != null
            ? tokenRequest.getScope()
            : new Scope(client.scopes().toArray(new String[0]));
        if (!clientService.isScopeAllowed(client, scope.toStringList())) {
            throw new OidcException(OAuth2Error.INVALID_SCOPE);
        }

        // Service principal: subject = client id. Roles are configured on the client
        // itself (oidc_client.roles) — machine identities are OIDC clients (Applications),
        // not pseudo-users in the user directory. This replaces the old machine-identity
        // user hack (2.0.36). The project is determined by the requesting client's
        // project_id, and the client's roles are scoped to that project.
        // An INACTIVE client is refused.
        if (!client.active()) {
            throw new OidcException(OAuth2Error.UNAUTHORIZED_CLIENT.appendDescription(
                ": service account is inactive"));
        }
        List<String> roles = client.roles() != null ? client.roles() : List.of();
        BearerAccessToken accessToken = tokenService.issueAccessToken(
            client.clientId(), client.clientId().getValue(), client.clientId().getValue(), null, roles, scope);

        AccessTokenResponse response = new AccessTokenResponse(
            new com.nimbusds.oauth2.sdk.token.Tokens(accessToken, null));
        return HttpResponse.ok(toMap(response.toJSONObject()));
    }

    private HttpResponse<?> refreshTokenToken(
        OidcClientService.OidcClient client,
        TokenRequest tokenRequest,
        RefreshTokenGrant grant
    ) {
        OidcTokenService.StoredToken stored = tokenService.validateRefreshToken(
            grant.getRefreshToken().getValue(), client.clientId().getValue());

        // Rotate the refresh token.
        tokenService.revoke(stored.value());

        List<String> scopes = stored.scopes();
        if (tokenRequest.getScope() != null) {
            List<String> requested = tokenRequest.getScope().toStringList();
            if (!clientService.isScopeAllowed(client, requested)) {
                throw new OidcException(OAuth2Error.INVALID_SCOPE);
            }
            scopes = requested;
        }
        Scope scope = new Scope(scopes.toArray(new String[0]));

        OidcUserService.OidcUser user = userService.bySubject(stored.subject(), client.projectId());
        BearerAccessToken accessToken = tokenService.issueAccessToken(
            client.clientId(), user.sub(), user.name(), user.email(), user.roles(), scope);
        RefreshToken refreshToken = tokenService.issueRefreshToken(client.clientId(), user.sub(), scope);

        if (scope.contains("openid")) {
            SignedJWT idToken = tokenService.issueIdToken(
                client.clientId(), user.sub(), user.name(), user.email(), user.roles(), null);
            OIDCTokenResponse response = new OIDCTokenResponse(new OIDCTokens(idToken, accessToken, refreshToken));
            return HttpResponse.ok(toMap(response.toJSONObject()));
        }
        AccessTokenResponse response = new AccessTokenResponse(
            new com.nimbusds.oauth2.sdk.token.Tokens(accessToken, refreshToken));
        return HttpResponse.ok(toMap(response.toJSONObject()));
    }

    // ------------------------------------------------------------------------
    // GET/POST /oidc/userinfo
    // ------------------------------------------------------------------------

    @Get("/userinfo")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> userInfoGet(HttpRequest<?> request) {
        return userInfo(request);
    }

    @Post("/userinfo")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> userInfoPost(HttpRequest<?> request) {
        return userInfo(request);
    }

    private HttpResponse<?> userInfo(HttpRequest<?> request) {
        String authorization = request.getHeaders().get("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.UNAUTHORIZED).body("Missing or invalid Bearer token");
        }
        String accessToken = authorization.substring(7);

        final JWTClaimsSet claims;
        try {
            claims = tokenService.validateAccessToken(accessToken);
        } catch (OidcException e) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.UNAUTHORIZED).body(e.getError().getCode());
        }

        UserInfo userInfo = new UserInfo(new Subject(claims.getSubject()));
        try {
            String name = claims.getStringClaim("name");
            if (name != null) {
                userInfo.setClaim("name", name);
            }
            String email = claims.getStringClaim("email");
            if (email != null) {
                userInfo.setClaim("email", email);
            }
            List<String> roles = claims.getStringListClaim("roles");
            if (roles != null && !roles.isEmpty()) {
                userInfo.setClaim("roles", roles);
            }
        } catch (java.text.ParseException ignored) {
            // claim present but not of the expected type — omit it
        }
        return HttpResponse.ok(toMap(userInfo.toJSONObject()));
    }

    // ------------------------------------------------------------------------
    // POST /oidc/introspect
    // ------------------------------------------------------------------------

    @Post("/introspect")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> introspect(HttpRequest<?> request, @Body Map<String, String> form) {
        final TokenIntrospectionRequest introspectionRequest;
        try {
            HTTPRequest nimbus = NimbusHttpAdapter.toNimbusFormPost(request, form, ContentType.APPLICATION_URLENCODED);
            introspectionRequest = TokenIntrospectionRequest.parse(nimbus);
        } catch (ParseException e) {
            return HttpResponse.badRequest("Invalid introspection request");
        }

        String value = introspectionRequest.getToken().getValue();
        Optional<OidcTokenService.StoredToken> stored = tokenService.findByValue(value);

        boolean active = stored.isPresent()
            && !stored.get().revoked()
            && (stored.get().expiresAt() == null || stored.get().expiresAt().isAfter(java.time.Instant.now()));

        TokenIntrospectionSuccessResponse.Builder builder = new TokenIntrospectionSuccessResponse.Builder(active);
        if (active) {
            OidcTokenService.StoredToken token = stored.get();
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
            builder.issuer(new Issuer(configuration.getIssuer()));
            if (token.subject() != null) {
                builder.subject(new Subject(token.subject()));
            }
            builder.audience(List.of(new Audience(token.clientId())));
        }
        TokenIntrospectionSuccessResponse response = builder.build();
        return HttpResponse.ok(toMap(response.toJSONObject()));
    }

    // ------------------------------------------------------------------------
    // POST /oidc/revoke
    // ------------------------------------------------------------------------

    @Post("/revoke")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> revoke(HttpRequest<?> request, @Body Map<String, String> form) {
        try {
            HTTPRequest nimbus = NimbusHttpAdapter.toNimbusFormPost(request, form, ContentType.APPLICATION_URLENCODED);
            TokenRevocationRequest revocationRequest = TokenRevocationRequest.parse(nimbus);
            tokenService.revoke(revocationRequest.getToken().getValue());
            return HttpResponse.ok();
        } catch (ParseException e) {
            return HttpResponse.badRequest("Invalid revocation request");
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Authenticates the client from the token request; throws {@code invalid_client} otherwise.
     *
     * <p>
     * Confidential clients use {@code client_secret_basic}/{@code client_secret_post}. Public
     * clients (stored with an empty secret — dsh-ui mobile, dsh-pc) send no client authentication
     * at all and identify themselves with the {@code client_id} form parameter; their proof of
     * possession is the PKCE verifier, enforced per-grant in {@link #token}.
     */
    private ClientID authenticateClient(TokenRequest tokenRequest, Map<String, String> form) {
        ClientAuthentication authentication = tokenRequest.getClientAuthentication();
        if (authentication == null) {
            String explicitClientId = form.get("client_id");
            if (explicitClientId == null || explicitClientId.isBlank()) {
                throw new OidcException(OAuth2Error.INVALID_CLIENT.appendDescription(
                    ": client authentication required (or client_id for public clients)"));
            }
            OidcClientService.OidcClient client = clientService.require(explicitClientId);
            if (!clientService.isPublic(client)) {
                throw new OidcException(OAuth2Error.INVALID_CLIENT.appendDescription(
                    ": confidential clients must authenticate with client_secret (basic/post)"));
            }
            return client.clientId();
        }
        ClientID clientId = authentication.getClientID();
        Secret secret = null;
        if (authentication instanceof ClientSecretBasic clientSecretBasic) {
            secret = clientSecretBasic.getClientSecret();
        } else if (authentication instanceof ClientSecretPost clientSecretPost) {
            secret = clientSecretPost.getClientSecret();
        }
        if (!clientService.authenticate(clientId.getValue(), secret != null ? secret.getValue() : null)) {
            throw new OidcException(OAuth2Error.INVALID_CLIENT);
        }
        return clientId;
    }

    private HttpResponse<?> authorizeError(URI redirectUri, State state, ErrorObject error) {
        AuthenticationErrorResponse response = new AuthenticationErrorResponse(
            redirectUri, error, state, ResponseMode.QUERY);
        return HttpResponse.redirect(response.toURI());
    }

    /** Redirects to the Kestra login page, preserving the current authorize request as {@code from}. */
    private HttpResponse<?> redirectToLogin(HttpRequest<?> request) {
        String from = request.getUri().toString();
        String loginUri = configuration.getLoginUrl()
            + "?from=" + URLEncoder.encode(from, StandardCharsets.UTF_8);
        return HttpResponse.redirect(URI.create(loginUri));
    }

    private HttpResponse<?> tokenErrorResponse(ErrorObject error) {
        TokenErrorResponse response = new TokenErrorResponse(error);
        int status = error.getHTTPStatusCode() > 0 ? error.getHTTPStatusCode() : 400;
        return HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(status)).body(toMap(response.toJSONObject()));
    }

    /** Converts a Nimbus {@code net.minidev.json.JSONObject} into a plain {@link LinkedHashMap}. */
    private static Map<String, Object> toMap(net.minidev.json.JSONObject jsonObject) {
        return new LinkedHashMap<>(jsonObject);
    }

    /** Materialises the request parameters (query + form) into a {@code Map<String,List<String>>}. */
    private static Map<String, List<String>> paramsMap(HttpRequest<?> request) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : request.getParameters()) {
            params.put(entry.getKey(), entry.getValue());
        }
        return params;
    }
}
