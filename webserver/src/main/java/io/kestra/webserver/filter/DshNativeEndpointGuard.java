package io.kestra.webserver.filter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * dsh fork（2026-09-28 安全评审 P0-2/P1-1，2026-09-29 复核扩展）：原生管理面收紧。
 *
 * <p>
 * 评审实证（alice，roles=[user]，JWT cookie）：
 * <ul>
 *   <li>user 可 {@code POST /api/v1/{tenant}/executions/{ns}/{flowId}} 原生触发 exec-run
 *       （绕过 DshExecController 的归属推导/格式校验/textB64/fileRefs 白名单）；</li>
 *   <li>user 有完整 flow 写权限（创建/改/删 flow → docker.sock 自建 flow = 宿主机 RCE 链）；</li>
 *   <li>user 可 {@code GET /api/v1/{tenant}/executions/search} 读全部执行记录
 *       （含他人 inputs.text）。</li>
 * </ul>
 *
 * <p>
 * 收紧语义（fork-only 新文件，零上游改写）——产品裁定 Kestra UI/原生 API 是管理员面，
 * 员工只走手机端 dsh 通道：
 * <ul>
 *   <li>{@code /api/v1/{tenant}/executions/**}：非 admin 一律 403（读+写）。执行记录全在
 *       dsh.exec namespace 下仅靠 dsh.sub label 区分归属，label 级过滤在 filter 层无法
 *       可靠注入（搜索为 PHP 风格嵌套 query 绑定），整面拒绝是唯一干净边界。连带效应：
 *       webhook 端点（executions/webhook/**）对非 admin/匿名关闭——本部署无任何 flow
 *       使用 webhook trigger（评审 grep 实证），属攻击面收敛而非功能损失。</li>
 *   <li>{@code /api/v1/{tenant}/flows/**}：非 admin 禁全部写（POST/PUT/DELETE），仅放行
 *       只读型 POST（编辑器预览/校验/导出，见 {@link #READONLY_FLOW_POST_SUFFIXES}）；
 *       GET 放行（flow 源码无秘密值，secret 以 {{ secret() }} 引用形式存在）。</li>
 * </ul>
 *
 * <p>
 * 路径判定用解码+归一化后的 URI（防 {@code /api/v1/main/%66lows} 编码绕过，同
 * AuthenticationFilter 对 GHSA-rjhm-qm6w-m7x9 的处理）；filter pattern 放宽到
 * {@code /api/v1/**} 由代码内细分，{@code /api/v1/executions/dsh/**}（dsh 容器执行面，
 * Bearer 认证）与 {@code /api/v1/dsh/**} 不匹配任一管理面正则，不受影响。
 *
 * <p>
 * 角色来源：JWT cookie（OIDC login 写入，roles claim = IdP 目录角色）。admin 放行全部；
 * 未登录请求已被上游 AuthenticationFilter 拦截（不会到达此 filter）。
 */
@Filter("/api/v1/**")
@Requires(property = "kestra.server-type", pattern = "(WEBSERVER|STANDALONE)")
public class DshNativeEndpointGuard implements HttpServerFilter {

    private static final String JWT_COOKIE = "JWT";
    private static final String ADMIN_ROLE = "admin";

    /** flows 下允许非 admin 的 POST：只读型编辑器预览/校验/表达式提示/导出。 */
    private static final Set<String> READONLY_FLOW_POST_SUFFIXES = Set.of(
        "graph",                       // parse a source for graph preview
        "source/replace/preview",      // search-replace preview (persists nothing)
        "validate/task",
        "validate/trigger",
        "expressions",                 // No-Code editor autocompletion hints
        "export/by-ids"                // export selected flows (read-only)
    );

    @Override
    public int getOrder() {
        // 在 SecurityFilter（认证）之后、业务 Controller 之前
        return ServerFilterPhase.SECURITY.order() + 10;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String path = normalizedPath(request);

        boolean executionsArea = isGuardedExecutions(path);
        boolean flowsArea = path != null && path.matches("^/api/v1/([^/]+/)?flows(/.*|$)");
        if (!executionsArea && !flowsArea) {
            return chain.proceed(request);
        }

        String method = request.getMethod().name();
        if ("HEAD".equals(method) || "OPTIONS".equals(method)) {
            return chain.proceed(request);
        }

        if (isAdminCaller(request)) {
            return chain.proceed(request);
        }

        if (flowsArea && "GET".equals(method)) {
            return chain.proceed(request);
        }
        if (flowsArea && "POST".equals(method) && READONLY_FLOW_POST_SUFFIXES.contains(suffixAfterFlows(path))) {
            return chain.proceed(request);
        }

        String guidance = executionsArea
            ? "execution management is admin-only; use POST /api/v1/dsh/exec/run for cloud tasks"
            : "flow management is admin-only";
        return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            .body(Map.of(
                "error", "forbidden",
                "error_description", guidance)));
    }

    /**
     * Guarded executions area. Two shapes reach ExecutionController：
     * <ul>
     *   <li>{@code /api/v1/{tenant}/executions/**}（tenantful，直连路由）；</li>
     *   <li>{@code /api/v1/executions/**}（tenant-less——TenantAliasingRooter 在路由层重写为
     *       main 租户，但 filter 看到的是 raw path，必须另行匹配，否则非 admin 可经无租户
     *       形式绕过）。例外：{@code /api/v1/executions/dsh/**} 是 dsh 容器执行面
     *       （OidcBearerAuthFilter Bearer 认证，手机端 PC 模式在用），不在守卫范围。</li>
     * </ul>
     */
    private static boolean isGuardedExecutions(String path) {
        if (path == null) {
            return false;
        }
        if (path.matches("^/api/v1/[^/]+/executions(/.*|$)")) {
            return true;
        }
        if (path.startsWith("/api/v1/executions")) {
            String rest = path.substring("/api/v1/executions".length());
            if (rest.isEmpty() || rest.startsWith("/")) {
                return !(rest.equals("/dsh") || rest.startsWith("/dsh/"));
            }
        }
        return false;
    }

    /** Decoded + {@code //}-collapsed path（编码/多斜杠绕过防护）；null when unparsable. */
    private static String normalizedPath(HttpRequest<?> request) {
        try {
            String path = request.getUri().getPath();
            return path == null ? null : path.replaceAll("/+", "/");
        } catch (Exception e) {
            return null;
        }
    }

    /** Path after the {@code .../flows} segment, no leading slash（"validate/task" for a preview call）. */
    private static String suffixAfterFlows(String path) {
        int idx = path.indexOf("/flows/");
        String rest = idx >= 0 ? path.substring(idx + "/flows/".length())
            : path.endsWith("/flows") ? "" : path;
        return rest;
    }

    /**
     * Parses the JWT payload (cookie or Authorization Bearer header) to check the admin role.
     * Signature trust: Micronaut SecurityFilter（intercept-url-map isAuthenticated）已在更早
     * 的 order 验签并拒绝无效 token——到达本 filter 的 JWT 均为共享密钥签发的有效令牌，
     * 这里只读 roles claim（与 DshEmployeeController 同款逻辑；Bearer 通道供
     * init:flows 等脚本客户端使用，其 token 同样经 Micronaut 验签）。
     */
    private static boolean isAdminCaller(HttpRequest<?> request) {
        String token = null;
        var cookies = request.getCookies();
        if (cookies != null) {
            var jwt = cookies.findCookie(JWT_COOKIE);
            if (jwt.isPresent()) {
                token = jwt.get().getValue();
            }
        }
        if (token == null) {
            token = request.getHeaders().getAuthorization()
                .filter(authorization -> authorization.startsWith("Bearer "))
                .map(authorization -> authorization.substring("Bearer ".length()))
                .orElse(null);
        }
        if (token == null) {
            return false;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class);
            Object roles = claims.get("roles");
            return roles instanceof List<?> list
                && list.stream().map(String::valueOf).anyMatch(ADMIN_ROLE::equals);
        } catch (Exception e) {
            return false;
        }
    }
}
