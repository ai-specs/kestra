package io.kestra.webserver.controllers.api;

import java.util.Objects;
import java.util.Optional;

import io.kestra.webserver.services.UiIndexService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.inject.Inject;

/**
 * Serves the standalone dsh Apps shell at {@code /apps/**} — outside the Kestra {@code /ui/} SPA.
 * Every {@code /apps/{app}/{page}} path returns the same rewritten {@code apps.html}; the entry
 * script reads the app/page ids from the URL and renders the amis page against {@code /api/v1/apps}.
 *
 * <p>Authentication is the deployment-wide SecurityFilter (docker-compose {@code intercept-url-map}
 * {@code /** → isAuthenticated()}): /apps/** matches no anonymous pattern, so an unauthenticated
 * browser request is 307-redirected to {@code /oidc/login} by {@code OidcAuthorizationExceptionHandler}
 * (Accept: text/html) and an API client gets 401 — the same OIDC gate as every page, asset and API.
 * Authenticated requests carry the JWT cookie and pass through to this controller unchanged.
 *
 * <p>Static assets referenced by the shell resolve to {@code /ui/assets/...} (base-path rewrite in
 * UiIndexService) and are served by {@link UiController}; nothing else is mounted under /apps.
 */
@Controller("/apps")
@Requires(property = "kestra.webserver.ui.enabled", notEquals = "false", defaultValue = "true")
@Hidden
public class UiAppController {

    private final UiIndexService uiIndexService;

    @Inject
    public UiAppController(UiIndexService uiIndexService) {
        this.uiIndexService = Objects.requireNonNull(uiIndexService);
    }

    @Get
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<?> index(HttpRequest<?> request) {
        return renderApps(request);
    }

    @Get("/{path:.*}")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<?> serve(HttpRequest<?> request, @PathVariable String path) {
        return renderApps(request);
    }

    private HttpResponse<?> renderApps(HttpRequest<?> request) {
        Optional<? extends HttpResponse<?>> index = uiIndexService.renderApps(request);
        return index.isPresent() ? index.get() : HttpResponse.notFound();
    }
}
