package io.kestra.webserver.filter;

import java.util.List;
import java.util.Map;

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
 * dsh fork（2026-09-28 安全评审 P0-2/P1-1）：原生 ExecutionController 管理面收紧。
 *
 * <p>
 * 评审实证：user 角色（手机端普通用户）可经原生 {@code POST /api/v1/{tenant}/executions/{ns}/{flowId}}
 * 直接触发 exec-run flow（绕过 DshExecController 的全部防护），也可 {@code GET /api/v1/{tenant}/executions}
 * 读取全部执行记录（含他人 inputs.text）。
 *
 * <p>
 * 本守卫在 filter 层收紧（fork-only 新文件，零上游改写）：
 * <ul>
 *   <li>非 admin 的写操作（POST/PUT/DELETE）到 {@code /api/v1/{tenant}/executions/**} → 403；
 *   <li>非 admin 的读操作（GET）到 executions 列表/详情 → 允许（UI 需要查看自己的执行），
 *       但非 admin 无法触发/取消/删除（写路径全拦）。</li>
 * </ul>
 *
 * <p>
 * 角色来源：JWT cookie（OIDC login 写入，roles claim = IdP 目录角色）。admin 放行全部
 * （管理面不变）；未登录用户已被上游 SecurityFilter 拦截（不会到达此 filter）。
 *
 * <p>
 * 透明度：DshExecController（/api/v1/dsh/exec/**）不经过此 filter（路径不匹配），手机端
 * 云电脑链路不受影响。
 */
@Filter("/api/v1/*/executions/**")
@Requires(property = "kestra.server-type", pattern = "(WEBSERVER|STANDALONE)")
public class DshNativeEndpointGuard implements HttpServerFilter {

    private static final String JWT_COOKIE = "JWT";
    private static final String BASIC_AUTH_COOKIE = "BASIC_AUTH";
    private static final String ADMIN_ROLE = "admin";

    @Override
    public int getOrder() {
        // 在 SecurityFilter（认证）之后、业务 Controller 之前
        return ServerFilterPhase.SECURITY.order() + 10;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String method = request.getMethod().name();
        // 只拦写操作（POST/PUT/DELETE/PATCH）；GET（读）暂不拦——P1-1 后续可加 label 过滤
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return chain.proceed(request);
        }

        if (isAdminCaller(request)) {
            return chain.proceed(request);
        }

        return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            .body(Map.of(
                "error", "forbidden",
                "error_description", "execution management is admin-only; use POST /api/v1/dsh/exec/run for cloud tasks")));
    }

    /** Parses the JWT cookie payload to check admin role（与 DshEmployeeController 同款逻辑）. */
    private static boolean isAdminCaller(HttpRequest<?> request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        var jwt = cookies.findCookie(JWT_COOKIE)
            .or(() -> cookies.findCookie(BASIC_AUTH_COOKIE));
        if (jwt.isEmpty()) {
            return false;
        }
        try {
            String[] parts = jwt.get().getValue().split("\\.");
            if (parts.length != 3) {
                // BASIC_AUTH cookie（user:pass base64）不是 JWT——非 admin
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
