package io.kestra.webserver.filter;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * DshNativeEndpointGuard 非 admin 白名单矩阵单测——「默认全拒 + 显式白名单」的
 * 回归锁：白名单条目必须逐字面命中；management 端点按名枚举（不按 @Endpoint 整类
 * 放行，2026-09-29 查漏收窄）；任何未列举面（含上游未来新增、目录管理面、员工
 * 管理面）默认拒绝。
 */
class DshNativeEndpointGuardTest {

    // ── 白名单面（逐项命中）──────────────────────────────────────────────────

    @Test
    void allowsOidcAndDiscoverySurfaces() {
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/oidc"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/oidc/login"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/oidc/token"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/.well-known/openid-configuration"), is(true));
        // 段边界：近似前缀不放行
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/oidc-evil"), is(false));
    }

    @Test
    void allowsDshControllerEnumeratedSurfaces() {
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/dsh/exec/run"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/dsh/gateway/x"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/dsh/metrics/x"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/executions/dsh/trigger"), is(true));
        // 宽 dsh 前缀不放行（与原生 {tenant} 路由变量空间重叠——编码绕过面）
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/dsh/unknown"), is(false));
        // tenantful executions/dsh 是原生 create 路径，属被拒面
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/executions/dsh/x"), is(false));
    }

    @Test
    void allowsMcpHealthPrometheusOnly() {
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/mcp"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/mcp/sse"), is(true));
        // mcp-servers 管理面不在此列
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/mcp-servers"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/health"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/health/liveness"), is(true));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/prometheus"), is(true));
    }

    // ── 默认全拒面（回归锁：任何未列举面一律拒绝）────────────────────────────

    @Test
    void deniesEverythingElseByDefault() {
        // management 端点按名枚举——env/heapdump/loggers 不在白名单（整类放行已收窄）
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/env"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/heapdump"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/loggers"), is(false));
        // 原生 API / UI / 目录管理面 / webhook / 员工管理面
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/flows/search"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/main/dsh-employee/list"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/oidc/users"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/api/v1/executions/webhook/abc"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/ui/main/employee"), is(false));
        assertThat(DshNativeEndpointGuard.isNonAdminAllowedSurface("/apps"), is(false));
    }
}
