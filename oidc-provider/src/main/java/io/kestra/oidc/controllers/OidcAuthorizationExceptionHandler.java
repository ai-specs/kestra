package io.kestra.oidc.controllers;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.security.authentication.AuthorizationException;
import jakarta.inject.Singleton;

/**
 * Handles Micronaut Security's {@link AuthorizationException} (thrown by SecurityFilter when an
 * unauthenticated / unauthorized request hits a protected path).
 *
 * <p>
 * Kestra's own {@code ErrorController} registers a global {@code Throwable} handler that would turn
 * every AuthorizationException into a 500. This bean uses {@code @Error(global = true)} with the
 * more specific {@link AuthorizationException} type — Micronaut dispatches by exception-type
 * specificity, so this handler wins over the {@code Throwable} catch-all and returns the intended
 * response:
 * <ul>
 *   <li>Browser requests (Accept: text/html) are 307-redirected to the IdP login page
 *     {@code /oidc/login} — carrying the original path as {@code ?from=} so a re-login lands
 *     the browser back on the page it asked for (the login form only honours same-origin
 *     absolute paths, so this cannot become an open redirect);</li>
 *   <li>API clients (JSON / anything else) get a plain 401 Unauthorized.</li>
 * </ul>
 * Part of the oidc-provider module; it does not modify Kestra upstream code.
 */
@Controller
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcAuthorizationExceptionHandler {

    /** The IdP login page (also {@code kestra.oidc.login-url}). */
    static final String LOGIN_URL = "/oidc/login";

    @Error(global = true)
    public HttpResponse<?> handle(HttpRequest<?> request, AuthorizationException exception) {
        // Only a navigation that explicitly accepts HTML is a browser (many API clients default
        // to Accept: */*, so that alone must NOT trigger a redirect — they get a plain 401).
        String accept = request.getHeaders().get(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains("text/html")) {
            return HttpResponse.temporaryRedirect(URI.create(loginUrlWithFrom(request)));
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED);
    }

    /** The IdP login URL carrying the denied path as the {@code from} deep link. */
    static String loginUrlWithFrom(HttpRequest<?> request) {
        String target = request.getUri().toString();
        if (target == null || target.isBlank() || !target.startsWith("/")) {
            return LOGIN_URL;
        }
        return LOGIN_URL + "?from=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
    }
}
