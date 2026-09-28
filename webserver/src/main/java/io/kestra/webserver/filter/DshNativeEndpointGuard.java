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
import io.kestra.webserver.services.DshAdminAuthorizer;

import jakarta.inject.Inject;

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
 *   <li>{@code /api/v1/dsh/{exec,gateway,metrics}/**}——dsh 联动 + 云电脑（**逐控制器
 *       枚举字面前缀**，不用宽 {@code /api/v1/dsh} 前缀——它与原生 {@code {tenant}} 路由
 *       变量空间重叠，编码变体可借道穿到原生 Controller，见
 *       {@link #isNonAdminAllowedSurface} 注释）：OidcBearerAuthFilter 强制 Bearer，
 *       DshExecController 自带归属推导/白名单/格式/textB64/fileRefs/并发防护；</li>
 *   <li>{@code /api/v1/executions/dsh/**}——dsh 容器执行面（无租户形式，手机端 PC 模式
 *       触发 dsh 命名空间 flow；OidcBearerAuthFilter 验 Bearer）。注意只放行无租户
 *       形式：tenantful {@code /api/v1/{tenant}/executions/dsh/**} 是原生 create 路径，
 *       属被拒面；</li>
 *   <li>{@code /api/v1/{tenant}/mcp/**}——MCP 协议端点（Streamable HTTP/SSE 传输，
 *       OidcMcpBearerAuthFilter 验 Bearer；{@code mcp-servers} 管理面不在此列，仍
 *       admin-only）；</li>
 *   <li>{@code /health/**}（含 liveness/readiness 子路径）、{@code /prometheus}——容器
 *       healthcheck / Prometheus 抓取（compose intercept-url-map 同样匿名）。管理端点
 *       按名枚举，不按 @Endpoint 注解整类放行（2026-09-29 查漏收窄——整类放行会把未来
 *       启用的 /env、/heapdump 等静默开放给已认证非 admin）。</li>
 * </ul>
 *
 * <p>
 * 其余一切（含 {@code /ui/**}、根级 apps 页面、全部 {@code /api/v1/**} 原生 API、
 * {@code /api/v1/oidc/**} 目录管理面、webhook）非 admin 一律 403。webhook 关闭是
 * 连带效应（本部署无 flow 使用 webhook trigger，属攻击面收敛）。
 *
 * <p>
 * 路径判定（2026-09-29 自审修正）：**raw 与 decoded 双视角都命中白名单才放行**
 * （fail-closed）。Micronaut 路由/Ant filter 匹配 RAW 请求目标，单看解码路径时
 * {@code /api/v1/%64sh/executions/search} 的 decoded 命中 {@code /api/v1/dsh} 白名单、
 * raw 却经 {@code {tenant}} 变量真实路由进原生 Controller（实测穿守卫，仅被下游
 * "Tenant must be main" 校验兜底）；反之只看 raw 则防不住已解码到达的形态。HEAD 与
 * 写方法同等对待（Micronaut 将 HEAD 路由到 GET 处理器真实执行，仅剥 body）。
 * 角色来源：JWT cookie 或 Authorization Bearer（均已被 Micronaut SecurityFilter 在更早
 * order 验签。admin 判定只读 SecurityFilter 注入的已验签 Authentication（见
 * DshAdminAuthorizer——2026-09-29 缺口审计：旧版自解析 payload 在匿名面可被伪造
 * roles=[admin] 的无效 token 骗过，已实测复现并修复）；Bearer 通道
 * 供 init:flows 等脚本客户端使用）。未登录请求已被上游拦截（浏览器 307 到
 * /oidc/login，API 401），不会到达本 filter。
 *
 * <p>
 * 生效条件（2026-09-28 CI 修复）：除 server-type 外，另要求
 * {@code micronaut.security.filter.enabled} 非 false。本守卫的 admin 判定依赖
 * SecurityFilter 注入的已验签 PRINCIPAL；SecurityFilter 被显式禁用时（webserver 单测
 * 上下文 application-test.yml），不存在认证语义，守卫默认全拒会误伤上游无认证 API
 * 测试（CI 曾致 576 失败）。生产 compose 不设此项（micronaut-security 默认 true），
 * 守卫照常生效。
 */
@Filter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "kestra.server-type", pattern = "(WEBSERVER|STANDALONE)")
@Requires(property = "micronaut.security.filter.enabled", notEquals = "false")
public class DshNativeEndpointGuard implements HttpServerFilter {

    private final DshAdminAuthorizer adminAuthorizer;

    /** 构造注入（可测性：doFilter 级单测直接实例化；生产由 Micronaut 调用）。 */
    DshNativeEndpointGuard(DshAdminAuthorizer adminAuthorizer) {
        this.adminAuthorizer = adminAuthorizer;
    }

    @Override
    public int getOrder() {
        // 在 SecurityFilter（认证）之后、业务 Controller 之前
        return ServerFilterPhase.SECURITY.order() + 10;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String method = request.getMethod().name();
        // OPTIONS = CORS 预检（无凭据、无用户数据）放行；HEAD 与写方法同等对待——
        // Micronaut 把 HEAD 路由到 GET 处理器真实执行（仅剥 body），无条件放行 = 存在性/
        // 大小 oracle（2026-09-29 自审实测 HEAD /api/v1/main/executions/search → 200）。
        if ("OPTIONS".equals(method)) {
            return chain.proceed(request);
        }

        if (adminAuthorizer.isAdmin(request)) {
            return chain.proceed(request);
        }

        // 双视角判定（2026-09-29 自审修正）：Micronaut 路由与 Ant-pattern filter 匹配的是
        // RAW 请求目标（request.getPath()），而 getUri().getPath() 是解码后的——单看解码
        // 路径时，/api/v1/%64sh/executions/search 的 decoded 命中 /api/v1/dsh 白名单、raw
        // 却经 {tenant} 变量真实路由进原生 ExecutionController（实测穿守卫，仅被下游
        // "Tenant must be main" 校验兜底）。两个视角都必须命中白名单才放行（fail-closed）。
        String raw = rawPath(request);
        String decoded = normalizedPath(request);
        if (raw != null && decoded != null
            && isNonAdminAllowedSurface(raw)
            && isNonAdminAllowedSurface(decoded)) {
            return chain.proceed(request);
        }

        if (raw == null || raw.startsWith("/api/") || (decoded != null && decoded.startsWith("/api/"))) {
            return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of(
                    // body 不枚举白名单（独立审计 F3：清单可被用于测绘；完整清单以
                    // isolation-boundaries.md #4 为准）
                    "error", "forbidden",
                    "error_description", "kestra ui and native apis are admin-only (dsh ruling 2026-09-29)")));
        }
        return Mono.just(HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            .contentType(io.micronaut.http.MediaType.TEXT_PLAIN_TYPE)
            .body("Kestra UI is admin-only."));
    }

    /**
     * 非 admin 放行面：OIDC 认证/发现、dsh 联动+云电脑（**逐控制器枚举**）、dsh 执行面、
     * MCP 协议端点、基础设施探针。
     *
     * <p>
     * dsh 面为什么枚举而不是 {@code /api/v1/dsh} 宽前缀（2026-09-29 自审）：原生路由是
     * {@code /api/v1/{tenant}/...} 形态，{@code tenant} 是变量——宽前缀会把
     * {@code /api/v1/dsh/executions/search}（decoded，tenant="dsh"）一并放进白名单，
     * 等于把原生 ExecutionController 挂到非 admin 可达面（实测：正常路径被
     * OidcBearerAuthFilter 的 raw 匹配 401 兜住，但 %64sh 编码变体同时躲开 Bearer
     * filter（raw 不匹配）与宽前缀守卫（decoded 命中），一路穿到原生 Controller，
     * 仅被 OSS 单租户校验 400 兜底）。枚举后 {@code /api/v1/dsh/executions} 不在
     * 放行集 → 守卫 403。
     *
     * <p>
     * <b>相位事实（2026-09-29 独立审计 F1，修正旧 javadoc 的过度声明）</b>：
     * {@code TenantValidationFilter}（上游 OSS，@RequestFilter）在 ROUTING 相位——
     * 早于本守卫（SECURITY+10）与 SecurityFilter。凡路由命中 {@code {tenant}} 变量
     * 且 tenant≠main 的路径（含 %64sh 编码变体），上游 400 短路在前，本守卫对该类
     * 请求**不评估**；无 {@code {tenant}} 变量匹配的路径（如 /api/v1/%64sh/exec/run）
     * 本守卫照常双视角 403。当前语义：非 admin 被上游 400 拦截（无数据暴露）；
     * 多租户化后 400 消失、本守卫恢复评估并 403——授权不因多租户失效，但「该类
     * 路径不依赖下游巧合」的旧声明不成立，实为双层分工：ROUTING 拦非法租户、
     * SECURITY+10 拦非 admin。
     */
    static boolean isNonAdminAllowedSurface(String path) {
        return hasPrefix(path, "/oidc")
            || hasPrefix(path, "/.well-known")
            || hasPrefix(path, "/api/v1/dsh/exec")
            || hasPrefix(path, "/api/v1/dsh/gateway")
            || hasPrefix(path, "/api/v1/dsh/metrics")
            || hasPrefix(path, "/api/v1/executions/dsh")
            || path.matches("^/api/v1/[^/]+/mcp(/.*|$)")
            // 管理端点**显式枚举**而非按 @Endpoint 注解整类放行（2026-09-29 查漏收窄）：
            // 整类放行会把未来任何被启用的 management 端点（/env、/heapdump、/loggers…
            // 可泄配置与线程转储）静默开放给全部已认证非 admin。/health 用前缀匹配以
            // 覆盖 liveness/readiness 子路径；/prometheus 精确匹配。启用新 management
            // 端点前必须先在此处显式放行（见 isolation-boundaries.md #4）。
            // /health 精确口径与 intercept-url-map 对齐（独立审计 F4：前缀匹配使
            // /health/liveness 对已认证非 admin 可达而匿名 401，两侧漂移）
            || path.equals("/health")
            || path.equals("/prometheus");
    }

    /** {@code path == prefix} or {@code path starts with prefix + "/"}（段边界，不放走 /oidc-evil）。 */
    private static boolean hasPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * RAW 请求目标（Netty 线上原样字节，不解码）。注意 {@code request.getPath()} 与
     * {@code getUri().getPath()} 在 Micronaut 里都返回**已解码**路径（实测 %64sh 解码为
     * dsh），拿不到线上原样——必须从 Netty 原始请求取（与路由层/Ant-pattern filter
     * 匹配的同一视角）。非 Netty 实现返回 null → 调用方 fail-closed 拒绝
     * （本 fork webserver 固定 netty，不受影响）。
     */
    private static String rawPath(HttpRequest<?> request) {
        try {
            if (request instanceof io.micronaut.http.server.netty.NettyHttpRequest<?> nettyRequest) {
                String target = nettyRequest.getNettyRequest().uri();
                // absolute-form（代理风格 http://host/path）剥 scheme+authority
                for (String scheme : new String[]{"http://", "https://"}) {
                    if (target.startsWith(scheme)) {
                        int slash = target.indexOf('/', scheme.length());
                        target = slash >= 0 ? target.substring(slash) : "/";
                        break;
                    }
                }
                int cut = target.indexOf('?');
                if (cut < 0) {
                    cut = target.indexOf('#');
                }
                String path = cut >= 0 ? target.substring(0, cut) : target;
                return path.isEmpty() ? "/" : path;
            }
        } catch (Exception e) {
            // fall through → null（fail-closed）
        }
        return null;
    }

    /** Decoded + {@code //}-collapsed path（java.net.URI 语义，%64→d）；null when unparsable. */
    private static String normalizedPath(HttpRequest<?> request) {
        try {
            String path = request.getUri().getPath();
            return path == null ? null : path.replaceAll("/+", "/");
        } catch (Exception e) {
            return null;
        }
    }

}
