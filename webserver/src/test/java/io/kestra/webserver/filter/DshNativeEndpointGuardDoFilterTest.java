package io.kestra.webserver.filter;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.http.HttpAttributes;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * DshNativeEndpointGuard doFilter 级单测——锁**运行时接线**而非仅静态谓词
 * （2026-09-29 独立审计 F2：静态谓词测试在注解/接线被删时依旧全绿）。
 *
 * <p>限制与覆盖说明：plain HttpRequest 非 Netty 实现，{@code rawPath} 恒 null →
 * 守卫 fail-closed 拒绝——这恰好覆盖「raw 视角不可得必须拒绝」的核心安全语义；
 * PRINCIPAL 注入模拟 SecurityFilter 已验签场景。守卫真实相位（SECURITY+10）与
 * TenantValidationFilter 的 ROUTING 前置由 e2e 契约 §6 锁定（静态单测无法覆盖
 * micronaut 相位排序）。
 */
class DshNativeEndpointGuardDoFilterTest {

    private static final DshNativeEndpointGuard GUARD = new DshNativeEndpointGuard(new DshAdminAuthorizerStub());

    /** 测试桩：与生产 DshAdminAuthorizer 同语义（只认 PRINCIPAL）。 */
    private static final class DshAdminAuthorizerStub extends io.kestra.webserver.services.DshAdminAuthorizer {
    }

    private static MutableHttpResponse<?> run(HttpRequest<?> request) {
        ServerFilterChain chain = req -> Mono.just(HttpResponse.ok("CONTROLLER_REACHED"));
        return Mono.from(GUARD.doFilter(request, chain)).block();
    }

    @Test
    void anonymousOnAnyPathIsRejectedBeforeController() {
        // 非 Netty → raw 视角不可得 → fail-closed：即使 decoded 命中白名单也拒绝
        MutableHttpResponse<?> response = run(HttpRequest.GET("/api/v1/dsh/exec/run"));
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatus().getCode(), is(403));
    }

    @Test
    void nonAdminPrincipalIsRejectedBeforeController() {
        HttpRequest<?> request = HttpRequest.GET("/api/v1/main/flows/search");
        request.setAttribute(HttpAttributes.PRINCIPAL, Authentication.build("alice@kestra.io", List.of("user")));
        MutableHttpResponse<?> response = run(request);
        assertThat(response.getStatus().getCode(), is(403));
    }

    @Test
    void adminPrincipalProceedsToController() {
        HttpRequest<?> request = HttpRequest.GET("/api/v1/main/flows/search");
        request.setAttribute(HttpAttributes.PRINCIPAL, Authentication.build("admin@kestra.io", List.of("admin")));
        MutableHttpResponse<?> response = run(request);
        assertThat(response.getStatus().getCode(), is(200));
        assertThat(response.body().toString(), is("CONTROLLER_REACHED"));
    }
}
