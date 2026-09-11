package io.kestra.webserver.controllers.api;

import java.util.Objects;
import java.util.Optional;

import io.kestra.webserver.services.UiIndexService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
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
 * Serves the standalone dsh Apps shell at {@code /{namespace}/** } — outside the Kestra
 * {@code /ui/} SPA. The URL carries the flow's namespace as its first segment:
 * {@code /{namespace}/{app}/{page}} (e.g. {@code /dsh.apps/hello/index}). Every such path
 * returns the same rewritten {@code apps.html}; the entry script reads namespace/app/page
 * ids from the URL and renders the amis page against {@code /api/v1/{namespace}}.
 * Legacy {@code /apps/{app}/{page}} URLs still render (the {@code apps} segment maps to
 * the convention root namespace in the front-end parser). The editor shell
 * {@code apps-editor.html} is served only for {@code /apps/pages-edit} — the hash carries
 * the editing target ({@code #dsh.apps/apps/{app}/{page}.json}); every other path renders
 * {@code apps.html} (design docs/dsh-apps-amis-editor.md §6.2).
 *
 * <p>Authentication is the deployment-wide SecurityFilter (docker-compose {@code intercept-url-map}
 * {@code /** → isAuthenticated()}): every path served here matches no anonymous pattern, so an
 * unauthenticated browser request is 307-redirected to {@code /oidc/login} by
 * {@code OidcAuthorizationExceptionHandler} (Accept: text/html) and an API client gets 401 —
 * the same OIDC gate as every page, asset and API. Authenticated requests carry the JWT cookie
 * and pass through to this controller unchanged.
 *
 * <p>Static assets referenced by the shell resolve to {@code /ui/assets/...} (base-path rewrite in
 * UiIndexService) and are served by {@link UiController}; nothing else is mounted here.
 */
@Controller("/{namespace}")
@Requires(property = "kestra.webserver.ui.enabled", notEquals = "false", defaultValue = "true")
@Hidden
public class UiAppController {

    /** 页面编辑入口：/apps/pages-edit#dsh.apps/apps/{app}/{page}.json —— hash 携带 namespace + 文件路径。 */
    static final String PAGES_EDIT_ENTRY = "pages-edit";

    private final UiIndexService uiIndexService;

    @Inject
    public UiAppController(UiIndexService uiIndexService) {
        this.uiIndexService = Objects.requireNonNull(uiIndexService);
    }

    @Get("/{path:.*}")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<?> serve(HttpRequest<?> request,
                                 @PathVariable String namespace,
                                 @PathVariable @Nullable String path) {
        // 只有 /apps/pages-edit 返回编辑器；其余全部渲染 apps.html（designer、/apps/{app}/{page}/edit
        // 等旧入口不再特殊，统一走普通渲染 —— 前端解析 URL 的 namespace/app/page 段）。
        if ("apps".equals(namespace) && PAGES_EDIT_ENTRY.equals(path)) {
            return renderAppEditor(request);
        }
        return renderApps(request);
    }

    private HttpResponse<?> renderApps(HttpRequest<?> request) {
        Optional<? extends HttpResponse<?>> index = uiIndexService.renderApps(request);
        return index.isPresent() ? index.get() : HttpResponse.notFound();
    }

    private HttpResponse<?> renderAppEditor(HttpRequest<?> request) {
        Optional<? extends HttpResponse<?>> index = uiIndexService.renderAppEditor(request);
        return index.isPresent() ? index.get() : HttpResponse.notFound();
    }
}
