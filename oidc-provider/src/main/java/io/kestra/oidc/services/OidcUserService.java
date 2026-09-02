package io.kestra.oidc.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import com.nimbusds.oauth2.sdk.OAuth2Error;

import io.kestra.oidc.OidcConfiguration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Resolves the OIDC user from the provider's own login session.
 *
 * <p>
 * This provider deliberately does <b>not</b> use Kestra's Basic Auth at all (Basic Auth re-sends
 * {@code username:password} with every request, which is why it is banned here). Instead:
 * <ul>
 *   <li>{@code POST /oidc/login} validates the credentials against the configured
 *       {@code kestra.oidc.admin-username}/{@code admin-password} and creates a server-side
 *       {@link OidcSessionService session};</li>
 *   <li>{@link #authenticatedUser(HttpRequest)} resolves the user from the {@code oidc_session}
 *       cookie only.</li>
 * </ul>
 * The username (an email) becomes the {@code sub}/{@code name}/{@code email} claims, and the
 * configured {@code kestra.oidc.default-roles} become the {@code roles} claim.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcUserService {

    /** An OIDC user resolved from the provider's login session. */
    public record OidcUser(String sub, String name, String email, List<String> roles) {}

    private final OidcSessionService sessionService;
    private final OidcConfiguration configuration;

    @Inject
    public OidcUserService(OidcSessionService sessionService, OidcConfiguration configuration) {
        this.sessionService = sessionService;
        this.configuration = configuration;
    }

    /**
     * Validates a username/password against the configured IdP accounts (the administrator plus
     * any {@code kestra.oidc.users} entries) using constant-time comparisons. Used by the IdP
     * login form — credentials are submitted once and exchanged for a session cookie, never
     * re-sent on later requests.
     */
    public boolean validateCredentials(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        if (matches(configuration.getAdminUsername(), configuration.getAdminPassword(), username, password)) {
            return true;
        }
        for (OidcConfiguration.OidcUserAccount account : configuration.getUsers()) {
            if (matches(account.getUsername(), account.getPassword(), username, password)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String expectedUser, String expectedPassword, String username, String password) {
        if (expectedUser == null || expectedPassword == null) {
            return false;
        }
        return constantTimeEquals(expectedUser.trim(), username)
            && constantTimeEquals(expectedPassword, password);
    }

    /**
     * Returns the authenticated user for the request, resolved from the {@code oidc_session}
     * cookie — empty when the browser has not logged into the provider.
     */
    public Optional<OidcUser> authenticatedUser(HttpRequest<?> request) {
        Optional<String> sessionId = sessionService.sessionIdFrom(request);
        if (sessionId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> subject = sessionService.subject(sessionId.get());
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        String username = subject.get();
        return Optional.of(new OidcUser(
            username,
            username,
            username,
            configuration.getDefaultRoles()
        ));
    }

    /** Same as {@link #authenticatedUser(HttpRequest)} but throws {@code access_denied} when absent. */
    public OidcUser requireAuthenticatedUser(HttpRequest<?> request) {
        return authenticatedUser(request)
            .orElseThrow(() -> new OidcException(OAuth2Error.ACCESS_DENIED.appendDescription(": user is not authenticated")));
    }

    /**
     * Rebuilds a user profile from a known subject (e.g. the subject stored in an authorization
     * code or refresh token). The provider's user directory is the configured account list, so a
     * known subject maps back to its own roles, the administrator (or an unknown service subject,
     * e.g. a client-credentials client id) to the configured default roles.
     */
    public OidcUser bySubject(String subject) {
        for (OidcConfiguration.OidcUserAccount account : configuration.getUsers()) {
            if (account.getUsername() != null && account.getUsername().trim().equals(subject)) {
                return new OidcUser(subject, subject, subject, account.getRoles());
            }
        }
        return new OidcUser(subject, subject, subject, configuration.getDefaultRoles());
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aa, bb);
    }
}
