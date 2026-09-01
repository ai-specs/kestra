package io.kestra.oidc.services;

import java.util.List;
import java.util.Optional;

import com.nimbusds.oauth2.sdk.OAuth2Error;

import io.kestra.oidc.OidcConfiguration;
import io.kestra.webserver.services.BasicAuthService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Resolves the OIDC user from the Kestra existing user system (Basic Auth user).
 *
 * <p>
 * In this OSS fork there is no multi-user/role table: the single configured Basic Auth user is the
 * OIDC user source. Its username (an email) becomes the {@code sub}/{@code name}/{@code email}
 * claims, and the configured {@code kestra.oidc.default-roles} become the {@code roles} claim.
 *
 * <p>
 * {@link BasicAuthService} is injected as {@link Optional} because it is only active when Micronaut
 * Security is not enabled; when the Kestra self-bootstrap flips security on, no authenticated user
 * can be resolved and the authorize endpoint will fall back to a login redirect.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcUserService {

    /** An OIDC user resolved from the Kestra user system. */
    public record OidcUser(String sub, String name, String email, List<String> roles) {}

    private final Optional<BasicAuthService> basicAuthService;
    private final OidcConfiguration configuration;

    @Inject
    public OidcUserService(Optional<BasicAuthService> basicAuthService, OidcConfiguration configuration) {
        this.basicAuthService = basicAuthService;
        this.configuration = configuration;
    }

    /**
     * Returns the authenticated user for the request, or empty when the user is not logged into
     * Kestra.
     */
    public Optional<OidcUser> authenticatedUser(HttpRequest<?> request) {
        if (basicAuthService.isEmpty()) {
            return Optional.empty();
        }
        BasicAuthService service = basicAuthService.get();
        if (!service.isAuthenticated(request)) {
            return Optional.empty();
        }
        String username = service.credentials().getUsername();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        OidcUser user = new OidcUser(
            username,
            username,
            username,
            configuration.getDefaultRoles()
        );
        return Optional.of(user);
    }

    /** Same as {@link #authenticatedUser(HttpRequest)} but throws {@code access_denied} when absent. */
    public OidcUser requireAuthenticatedUser(HttpRequest<?> request) {
        return authenticatedUser(request)
            .orElseThrow(() -> new OidcException(OAuth2Error.ACCESS_DENIED.appendDescription(": user is not authenticated")));
    }

    /**
     * Rebuilds a user profile from a known subject (e.g. the subject stored in an authorization
     * code or refresh token). Since the Kestra OSS user system is a single Basic Auth user, the
     * subject (username/email) maps back to the configured default roles.
     */
    public OidcUser bySubject(String subject) {
        return new OidcUser(subject, subject, subject, configuration.getDefaultRoles());
    }
}
