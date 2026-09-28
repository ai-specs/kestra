package io.kestra.webserver.services;

import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * DshAdminAuthorizer 信任模型单测：admin 判定**只**认 SecurityFilter 注入请求属性的
 * 已验签 Authentication（HttpAttributes.PRINCIPAL）——2026-09-29 缺口审计的回归锁
 * （旧版自解析 JWT payload 在匿名面可被伪造 roles=[admin] 的无效 token 骗过，
 * 实测复现过用户目录泄露）。
 */
class DshAdminAuthorizerTest {

    private final DshAdminAuthorizer authorizer = new DshAdminAuthorizer();

    @Test
    void adminRoleInValidatedAuthenticationPasses() {
        HttpRequest<?> request = withPrincipal(Authentication.build("admin@kestra.io", List.of("admin")));
        assertThat(authorizer.isAdmin(request), is(true));
        // 多角色含 admin
        assertThat(authorizer.isAdmin(withPrincipal(Authentication.build("x", List.of("user", "admin")))), is(true));
    }

    @Test
    void nonAdminAuthenticationFails() {
        assertThat(authorizer.isAdmin(withPrincipal(Authentication.build("alice@kestra.io", List.of("user")))), is(false));
        assertThat(authorizer.isAdmin(withPrincipal(Authentication.build("x", List.of()))), is(false));
    }

    @Test
    void missingAuthenticationNeverAdmin() {
        // 匿名面 / 无效 token（SecurityFilter 不注入 PRINCIPAL）——即使攻击者伪造
        // roles=[admin] 的 cookie/Bearer，本判定也永远是 false
        assertThat(authorizer.isAdmin(HttpRequest.GET("/api/v1/oidc/users")), is(false));
    }

    private static HttpRequest<?> withPrincipal(Authentication authentication) {
        HttpRequest<?> request = HttpRequest.GET("/x");
        request.setAttribute(HttpAttributes.PRINCIPAL, authentication);
        return request;
    }
}
