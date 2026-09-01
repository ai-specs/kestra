package io.kestra.oidc.controllers;

import java.net.URI;

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
 *   <li>Browser requests (Accept: text/html) are 302-redirected to the IdP login page
 *       {@code /oidc/login} — the OIDC login flow entry;</li>
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
            return HttpResponse.temporaryRedirect(URI.create(LOGIN_URL));
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED);
    }
}
