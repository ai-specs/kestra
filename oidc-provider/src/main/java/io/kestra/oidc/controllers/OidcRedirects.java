package io.kestra.oidc.controllers;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;

import java.net.URI;

/**
 * Redirect helpers for the /oidc endpoints.
 *
 * <p>
 * All IdP redirects carry per-request state — session cookies (login/logout), one-time
 * authorization codes (authorize/callback), or a caller-supplied {@code from} target (login
 * page). Micronaut's {@link HttpResponse#redirect(URI)} answers 301 Moved Permanently with no
 * cache directives, and browsers cache a 301 heuristically: every later navigation to the same
 * path is served from cache, so the response's Set-Cookie clears never run again (observed: a
 * cached logout redirect let a revoked IdP session survive and silently re-login the same user).
 *
 * <p>
 * Every /oidc redirect therefore answers 302 Found — not heuristically cacheable — with an
 * explicit {@code Cache-Control: no-store}.
 */
final class OidcRedirects {

    private OidcRedirects() {}

    static MutableHttpResponse<?> temporary(URI location) {
        return HttpResponse.status(HttpStatus.FOUND)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.LOCATION, location.toString());
    }

    static MutableHttpResponse<?> temporary(String location) {
        return temporary(URI.create(location));
    }
}
