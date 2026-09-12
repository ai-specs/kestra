package io.kestra.webserver.controllers.api;

import java.util.Objects;
import java.util.Optional;

import io.kestra.core.tenant.TenantService;
import io.kestra.webserver.services.AppRouteRegistry;
import io.kestra.webserver.services.UiIndexService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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
 * The {@code apps} segment is a namespace like any other (a flow declared in a namespace
 * literally named {@code apps} resolves at {@code /apps/{app}/{page}}); there is no legacy
 * mapping to the convention root. The editor shell
 * {@code apps-editor.html} is served only for {@code /apps/pages-edit} — the query carries
 * the editing target ({@code ?namespace=..&appName=..&pagefileName=*.json}); every other path renders
 * {@code apps.html} (design docs/dsh-apps-amis-editor.md §6.2).
 *
 * <p>Page routing is backend-verified: a path that does not resolve to a registered page
 * route ({@code PageTrigger}) returns an HTTP-standard 404 with an empty body, so probing
 * unknown URLs never yields a 200 shell whose SPA then shows an error — the shell is served
 * only for pages that actually exist.
 *
 * <p>The catch-all deliberately does NOT claim prefixes already occupied by upstream
 * ({@code api}, {@code ui}, {@code app} — route regex below): unmatched paths under those
 * prefixes fall through to upstream's own route-miss handling (problem+json via
 * ErrorController), exactly as they would without this controller — never intercepted or
 * probed here. {@code apps} stays claimable: it is a namespace like any other and carries
 * the {@code /apps/pages-edit} editor entry.
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
// 命名空间通配不认领上游已占用前缀（api/ui/app）：serve() 首行短路，经上游异常管线
// 产出 problem+json 404——与上游 route-miss 行为一致，不拦截、不防探测（维护者要求，
// 2026-09-12）。注意不能用路由级负向前瞻 regex（{namespace:(?!...)...}）——Micronaut
// UriTemplateMatcher 对其 Pattern.compile 直接抛异常、服务起不来（实测）。
// apps 仍是可命中命名空间——它承载 /apps/pages-edit 编辑器入口。
@Controller("/{namespace}")
@Requires(property = "kestra.webserver.ui.enabled", notEquals = "false", defaultValue = "true")
@Hidden
public class UiAppController {

    /** 页面编辑入口：/apps/pages-edit?namespace=..&appName=..&pagefileName=*.json —— 查询参数携带编辑目标。 */
    static final String PAGES_EDIT_ENTRY = "pages-edit";

    private final UiIndexService uiIndexService;

    @Inject
    private AppRouteRegistry routeRegistry;

    @Inject
    private TenantService tenantService;

    public UiAppController(UiIndexService uiIndexService) {
        this.uiIndexService = Objects.requireNonNull(uiIndexService);
    }

    /** 上游已占用的顶级前缀：其下未命中路径不经本控制器防探测，交回上游处理。 */
    private static final java.util.Set<String> UPSTREAM_RESERVED_NAMESPACES = java.util.Set.of("api", "ui", "app");

    @Get("/{path:.*}")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<?> serve(HttpRequest<?> request,
                                 @PathVariable String namespace,
                                 @PathVariable @Nullable String path) {
        // 上游保留前缀短路：本通配在 Micronaut 路由解析中先于 route-miss 命中它们的
        // 未命中路径——按维护者要求不认领，经上游异常管线返回 problem+json 404
        // （与无本控制器时的 route-miss 行为等价）。
        if (UPSTREAM_RESERVED_NAMESPACES.contains(namespace)) {
            throw new io.micronaut.http.exceptions.HttpStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
        // 只有 /apps/pages-edit 返回编辑器（保留入口）；其余渲染页须能解析到注册的页面路由，
        // 否则抛空 body 404（防探测）——必须走异常管线（Kestra#17633），controller 内
        // raw-404 提前返回会在流式 body 未消费时触发 drain OOM（Kestra#17620）。
        if ("apps".equals(namespace) && PAGES_EDIT_ENTRY.equals(path)) {
            // 编辑目标 query 契约（?namespace&appName&pagefileName）：缺失/形态非法一律 404 空 body
            // （防探测：不泄露正确格式，也不下发编辑器 shell——探测者拿不到任何可用信息）。
            String ns = request.getParameters().get("namespace");
            String app = request.getParameters().get("appName");
            String page = request.getParameters().get("pagefileName");
            boolean wellFormed = ns != null && !ns.isBlank()
                && app != null && app.matches("[A-Za-z0-9_-]+")
                && page != null && page.matches("[A-Za-z0-9_./-]+\\.json");
            if (!wellFormed) {
                throw new NotFoundResponseException();
            }
            return renderAppEditor(request);
        }
        // index.json 如 index.html：目录式（尾斜杠结尾）是规范地址，直接渲染 index 页；
        // 其余形态——显式 /index 与无尾斜杠——一律 308 永久跳转到对应目录式。
        // 必须用原始 URI 判定（@PathVariable 的 path 变量会被 Micronaut 剥掉尾斜杠，
        // 依它判定会让规范形无限自跳）。
        String rawPath = request.getUri().getPath();
        if (!rawPath.endsWith("/") && !rawPath.equals("/apps/pages-edit")) {
            String target = rawPath.endsWith("/index")
                ? rawPath.substring(0, rawPath.length() - "index".length()) // /index → /（保留其前的 /）
                : rawPath + "/"; // 无尾斜杠目录根
            return HttpResponse.status(HttpStatus.PERMANENT_REDIRECT)
                .header("Location", target);
        }
        if (!pageRouteExists(namespace, path)) {
            throw new NotFoundResponseException();
        }
        return renderApps(request);
    }

    /**
     * 页面路由存在性：path 形如 {appName}/{pageId} 或 {appName}（pageId 缺省 index）。
     * 存在 = AppRouteRegistry 中有该 (namespace, appName, pageId) 的 PageTrigger 路由。
     */
    private boolean pageRouteExists(String namespace, @Nullable String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] parts = path.split("/", 2);
        if (parts.length > 1 && parts[1].contains("/")) {
            return false;
        }
        String appName = parts[0];
        String pageId = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "index";
        return !routeRegistry.pageRoutes(tenantService.resolveTenant(), namespace, appName, pageId).isEmpty();
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
