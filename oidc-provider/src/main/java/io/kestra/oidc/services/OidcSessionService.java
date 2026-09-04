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

    /**
     * Lifetime of a one-shot session (remember_session unchecked + SSO authorize flow): just
     * enough for the browser to complete the current {@code /oidc/authorize} round-trip; the
     * session is revoked server-side right after the authorization code is issued, and the short
     * cookie TTL makes sure the browser drops it on its own shortly after. Any subsequent SSO
     * therefore has to re-authenticate — "每次进入均需验证" is preserved.
     */
    public static final Duration ONE_SHOT_TTL = Duration.ofMinutes(2);

    /** In-memory session store: session id -> (subject, expiry). */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final OidcConfiguration configuration;

    @Inject
    public OidcSessionService(OidcConfiguration configuration) {
        this.configuration = configuration;
    }

    /** A session entry. {@code oneShot} sessions exist only to complete one authorize flow. */
    public record Session(String id, String subject, Instant expiresAt, boolean oneShot) {}

    /**
     * Creates a regular (reusable) session for the given subject and returns its opaque id.
     */
    public String create(String subject) {
        return create(subject, false, configuration.getSessionTtl());
    }

    /**
     * Creates a one-shot session: short TTL, never slid by {@link #extend}, and expected to be
     * revoked by the authorize endpoint right after the code is issued.
     */
    public String createOneShot(String subject) {
        return create(subject, true, ONE_SHOT_TTL);
    }

    private String create(String subject, boolean oneShot, Duration ttl) {
        String id = randomId();
        Instant expiresAt = Instant.now().plus(ttl);
        sessions.put(id, new Session(id, subject, expiresAt, oneShot));
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

    /**
     * Slides the session expiry forward to {@code now + ttl} (session refresh). Returns
     * {@code false} when the session is unknown or already expired — the caller then re-creates
     * one or rejects the request. One-shot sessions are never slid (they must die right after
     * their single authorize use).
     */
    public boolean extend(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Session session = sessions.get(sessionId);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return false;
        }
        if (session.oneShot()) {
            return false;
        }
        sessions.put(sessionId, new Session(session.id(), session.subject(), Instant.now().plus(configuration.getSessionTtl()), false));
        return true;
    }

    /**
     * Whether the session is a one-shot session (still valid, not expired). Used by the
     * authorize endpoint to revoke it right after issuing the authorization code.
     */
    public boolean isOneShot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Session session = sessions.get(sessionId);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return false;
        }
        return session.oneShot();
    }

    /** Removes a session (logout). */
    public void revoke(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Builds the session cookie for the given session id (regular session TTL). */
    public Cookie sessionCookie(HttpRequest<?> request, String sessionId) {
        return sessionCookie(request, sessionId, configuration.getSessionTtl());
    }

    /** Builds the session cookie with an explicit max-age (used for short-lived one-shot sessions). */
    public Cookie sessionCookie(HttpRequest<?> request, String sessionId, Duration maxAge) {
        return Cookie.of(SESSION_COOKIE_NAME, sessionId)
            .path("/")
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite(SameSite.Strict)
            .maxAge(maxAge);
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
