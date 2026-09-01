package io.kestra.oidc.controllers;

import java.net.URI;
import java.util.Optional;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcClientService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;

/**
 * The self-bootstrap redirect target at Micronaut's OAuth2 client callback convention
 * ({@code /oauth/callback/{registrationId}}) — the URI the {@code kestra-self} client registers
 * (see migration {@code 2.0.25-oidc-provider-postgres.sql}), valid whether or not the native
 * Micronaut OAuth2 client is ever enabled.
 *
 * <p>
 * The authorization code is consumed server-side: the browser session is the {@code oidc_session}
 * cookie established at the IdP login form, the code only proves this authorization round
 * completed. After consuming it the browser lands on the UI, authenticated by the JWT cookie the
 * login already issued.
 */
@Controller("/oauth/callback")
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
@ExecuteOn(TaskExecutors.IO)
public class OidcOAuthCallbackController {

    /** The self-bootstrap registration id (its callback path segment). */
    static final String SELF_REGISTRATION_ID = "kestra-oidc";

    private final OidcConfiguration configuration;
    private final OidcAuthorizationCodeService authCodeService;
    private final OidcClientService clientService;

    @Inject
    public OidcOAuthCallbackController(
        OidcConfiguration configuration,
        OidcAuthorizationCodeService authCodeService,
        OidcClientService clientService
    ) {
        this.configuration = configuration;
        this.authCodeService = authCodeService;
        this.clientService = clientService;
    }

    /** Only the self-bootstrap registration is served here; anything else is not ours. */
    @Get("/{registrationId}")
    public HttpResponse<?> callback(String registrationId,
                                    @QueryValue Optional<String> code,
                                    @QueryValue Optional<String> error) {
        if (!SELF_REGISTRATION_ID.equals(registrationId)) {
            return HttpResponse.notFound();
        }
        if (error.isPresent()) {
            return HttpResponse.badRequest("authorization failed: " + error.get());
        }
        if (code.isEmpty() || code.get().isBlank()) {
            return HttpResponse.badRequest("missing authorization code");
        }
        Optional<OidcClientService.OidcClient> client = clientService.find(OidcLoginController.SELF_CLIENT_ID);
        if (client.isEmpty() || client.get().redirectUris().isEmpty()) {
            return HttpResponse.badRequest("self-bootstrap client not configured: " + OidcLoginController.SELF_CLIENT_ID);
        }
        String redirectUri = client.get().redirectUris().get(0);
        try {
            authCodeService.consume(code.get(), OidcLoginController.SELF_CLIENT_ID, redirectUri, null);
        } catch (Exception e) {
            return HttpResponse.badRequest("invalid authorization code: " + e.getMessage());
        }
        return HttpResponse.redirect(URI.create(OidcLoginController.DEFAULT_LANDING));
    }
}
