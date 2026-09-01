package io.kestra.oidc.services;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.kestra.oidc.OidcConfiguration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Server-side session store for the OIDC Provider's own login (independent from Kestra's Basic
 * Auth — Basic Auth is deliberately not used anywhere in this provider).
 *
 * <p>
 * A successful {@code POST /oidc/login} creates a session and returns a single opaque
 * {@code oidc_session} cookie. Subsequent {@code /oidc/authorize} requests present that cookie
 * instead of re-sending credentials on every request (the reason Basic Auth is banned here).
 * Sessions are held in memory (single-node; the provider is co-located with Kestra in this
 * deployment) and expire after {@code kestra.oidc.session-ttl}.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcSessionService {

    /** The session cookie name. */
    public static final String SESSION_COOKIE_NAME = "oidc_session";

    /** In-memory session store: session id -> (subject, expiry). */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final OidcConfiguration configuration;

    @Inject
    public OidcSessionService(OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    /** A session entry. */
    public record Session(String id, String subject, Instant expiresAt) {}

    /**
     * Creates a session for the given subject and returns its opaque id.
     */
    public String create(String subject) {
        String id = randomId();
        Instant expiresAt = Instant.now().plus(configuration.getSessionTtl());
        sessions.put(id, new Session(id, subject, expiresAt));
        return id;
    }

    /**
     * Resolves the session id to its subject, removing expired sessions.
     */
    public Optional<String> subject(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(session.subject());
    }

    /** Removes a session (logout). */
    public void revoke(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Builds the session cookie for the given session id. */
    public Cookie sessionCookie(HttpRequest<?> request, String sessionId) {
        return Cookie.of(SESSION_COOKIE_NAME, sessionId)
            .path("/")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict)
            .maxAge(configuration.getSessionTtl());
    }

    /** Reads the session id from the request cookie (empty when absent). */
    public Optional<String> sessionIdFrom(HttpRequest<?> request) {
        return request.getCookies().findCookie(SESSION_COOKIE_NAME).map(Cookie::getValue);
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
