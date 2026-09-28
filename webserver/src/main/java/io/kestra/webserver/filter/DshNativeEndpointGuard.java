package io.kestra.webserver.filter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.RouteMatchUtils;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * dsh fork（维护者裁定 2026-09-29）：Kestra UI 及全部原生 API **仅 admin 可用**；
 * 非 admin 用户任何时候只能访问少数定制功能面——OIDC 认证、dsh 联动、云电脑 API。
 *
 * <p>
 * 演进：2026-09-28 首版只拦 executions 写（P0-2）；2026-09-29 上午扩展 flows 写 +
 * executions 读写；本版按裁定收敛为**默认全拒 + 显式白名单**（flows GET、plugins/
 * namespaces/blueprints 等其余原生 API 一并关闭）。产品语义：员工用手机端
 * （Bearer，dsh 面），Kestra UI/原生 API 是管理员运维面。
 *
 * <p>
 * 非 admin 白名单（均自校验或为基础设施探针）：
 * <ul>
 *   <li>{@code /oidc/**}、{@code /.well-known/**}——OIDC 认证与发现（登录/token/
 *       刷新/userinfo/登出，手机 PKCE 与 IdP 表单登录都走这里）；</li>
 *   <li>{@code /api/v1/dsh/**}——dsh 联动 + 云电脑（{@code exec/**}）：OidcBearerAuthFilter
 *       强制 Bearer（手机 PKCE / PC / dsh 服务身份），DshExecController 自带归属推导/
 *       白名单/格式/textB64/fileRefs/并发防护；{@code dsh/employee}、{@code dsh/gateway}
 *       等子面各自再做自身 admin 校验；</li>
 *   <li>{@code /api/v1/executions/dsh/**}——dsh 容器执行面（无租户形式，手机端 PC 模式
 *       触发 dsh 命名空间 flow；OidcBearerAuthFilter 验 Bearer）。注意只放行无租户
 *       形式：tenantful {@code /api/v1/{tenant}/executions/dsh/**} 是原生 create 路径，
 *       属被拒面；</li>
 *   <li>{@code /api/v1/{tenant}/mcp/**}——MCP 协议端点（Streamable HTTP/SSE 传输，
 *       OidcMcpBearerAuthFilter 验 Bearer；{@code mcp-servers} 管理面不在此列，仍
 *       admin-only）；</li>
 *   <li>{@code /health}、{@code /prometheus} 与 @Endpoint 管理端点——容器 healthcheck /
 *       Prometheus 抓取（compose intercept-url-map 同样匿名）。</li>
 * </ul>
 *
 * <p>
 * 其余一切（含 {@code /ui/**}、根级 apps 页面、全部 {@code /api/v1/**} 原生 API、
 * {@code /api/v1/oidc/**} 目录管理面、webhook）非 admin 一律 403。webhook 关闭是
 * 连带效应（本部署无 flow 使用 webhook trigger，属攻击面收敛）。
 *
 * <p>
 * 路径判定用解码+归一化 URI（防 {@code /api/v1/main/%66lows} 编码绕过，同
 * AuthenticationFilter 对 GHSA-rjhm-qm6w-m7x9 的处理）。角色来源：JWT cookie 或
 * Authorization Bearer（均已被 Micronaut SecurityFilter 在更早 order 验签，本 filter
 * 只读 roles claim；Bearer 通道供 init:flows 等脚本客户端使用）。未登录请求已被
 * 上游拦截（浏览器 307 到 /oidc/login，API 401），不会到达本 filter。
 */
@Filter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "kestra.server-type", pattern = "(WEBSERVER|STANDALONE)")
public class DshNativeEndpointGuard implements HttpServerFilter {

    private static final String JWT_COOKIE = "JWT";
    private static final String ADMIN_ROLE = "admin";

    @Override
    public int getOrder() {
        // 在 SecurityFilter（认证）之后、业务 Controller 之前
        return ServerFilterPhase.SECURITY.order() + 10;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String method = request.getMethod().name();
        if ("HEAD".equals(method) || "OPTIONS".equals(method)) {
            return chain.proceed(request);
        }

        String path = normalizedPath(request);
        if (path == null) {
            // 无法解析的 URI 由路由层拒绝；不在此扩大判定
            return chain.proceed(request);
        }

        if (isAdminCaller(request) || isNonAdminAllowedSurface(path, request)) {
            return chain.proceed(request);
        }

        if (path.startsWith("/api/")) {
            return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of(
                    "error", "forbidden",
                    "error_description", "kestra ui and native apis are admin-only"
                        + " (dsh ruling 2026-09-29); non-admin surfaces:"
                        + " /oidc/**, /api/v1/dsh/**, /api/v1/executions/dsh/**, /api/v1/{tenant}/mcp/**")));
        }
        return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            .contentType(io.micronaut.http.MediaType.TEXT_PLAIN_TYPE)
            .body("Kestra UI is admin-only."));
    }

    /** 非 admin 放行面：OIDC 认证/发现、dsh 联动+云电脑、dsh 执行面、MCP 协议端点、基础设施探针。 */
    private static boolean isNonAdminAllowedSurface(String path, HttpRequest<?> request) {
        return hasPrefix(path, "/oidc")
            || hasPrefix(path, "/.well-known")
            || hasPrefix(path, "/api/v1/dsh")
            || hasPrefix(path, "/api/v1/executions/dsh")
            || path.matches("^/api/v1/[^/]+/mcp(/.*|$)")
            || path.equals("/health")
            || path.equals("/prometheus")
            || isManagementEndpoint(request);
    }

    /** {@code path == prefix} or {@code path starts with prefix + "/"}（段边界，不放走 /oidc-evil）。 */
    private static boolean hasPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
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

    @SuppressWarnings("rawtypes")
    private static boolean isManagementEndpoint(HttpRequest<?> request) {
        Optional<RouteMatch> routeMatch = RouteMatchUtils.findRouteMatch(request);
        if (routeMatch.isPresent() && routeMatch.get() instanceof MethodBasedRouteMatch<?, ?> method) {
            return method.getAnnotation(Endpoint.class) != null;
        }
        return false;
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
