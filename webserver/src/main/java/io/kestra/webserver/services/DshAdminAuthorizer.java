package io.kestra.webserver.services;

import java.util.List;

import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;

import jakarta.inject.Singleton;

/**
 * dsh fork：**全站唯一**的「admin 角色判定点」——`DshNativeEndpointGuard`（面级默认
 * 全拒）等所有需要角色判定的调用方统一注入本类，禁止再手写 token/claims 解析。
 *
 * <p>
 * <b>信任模型（2026-09-29 缺口审计后收紧）</b>：只读 Micronaut SecurityFilter 写入
 * 请求属性的**已验签** {@link Authentication}（{@link HttpAttributes#PRINCIPAL}），
 * 不再自行解析 JWT/Bearer。历史教训：首版直接 base64 解 JWT payload 读 roles、不验签，
 * 依赖「SecurityFilter 已在更早相位验签」的假设——该假设在**匿名面不成立**（无效
 * token 在 isAnonymous() 规则下被当作匿名放行，PRINCIPAL 不存在，守卫却把自解析的
 * 伪造 roles 当真）：实测伪造 roles=[admin] + 垃圾签名的 cookie 在匿名面骗过守卫
 * admin 短路（连带下游缺陷泄出用户目录，已修复+回归）。改为只认框架注入的
 * Authentication 后：任何未经 SecurityFilter 验签的凭据在本判定下永远是「非 admin」，
 * 匿名面/伪造 token 均不可绕过。
 *
 * <p>
 * <b>演进缝隙（维护者裁定 2026-09-29）</b>：认证/授权模块不受 fork-only 约束——
 * OSS 的削弱是商业策略而非技术设计。未来按 OAuth2 规范重构（Kestra 作为 resource
 * server 校验第三方 IdP 的 access token：RFC 6750 Bearer / RFC 9068 JWT profile /
 * introspection）时，Authentication 的构建方式由 micronaut-security 相应插件替换，
 * 本判定点与 {@link #ROLE_ADMIN} 语义不变——本类即预留的替换点。
 */
@Singleton
public class DshAdminAuthorizer {

    /** admin 角色字面量（oidc_user.roles 与 JWT roles claim 同名）。 */
    public static final String ROLE_ADMIN = "admin";

    /**
     * 请求方是否持有 admin 角色。只信任 SecurityFilter 注入的已验签 Authentication；
     * 匿名面、无效/伪造 token、未携带凭据一律 false（fail-closed）。
     */
    public boolean isAdmin(HttpRequest<?> request) {
        return request.getAttribute(HttpAttributes.PRINCIPAL, Authentication.class)
            .map(auth -> auth.getRoles().contains(ROLE_ADMIN))
            .orElse(false);
    }

    /** 测试/内部用：构造带角色的 Authentication。 */
    static Authentication authenticationOf(String subject, List<String> roles) {
        return Authentication.build(subject, roles);
    }
}
