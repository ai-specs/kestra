package io.kestra.webserver.services;

import java.util.List;
import java.util.Map;

import io.micronaut.http.HttpRequest;

import jakarta.inject.Singleton;

/**
 * dsh fork：**全站唯一**的「admin 角色判定点」——`DshNativeEndpointGuard`（面级
 * 默认全拒）等所有需要角色判定的调用方统一注入本类，禁止再手写 JWT payload 解析
 * （历史上 filter 与 DshEmployeeController 各持一份同款实现，已合并到此处）。
 *
 * <p>
 * 信任链（显式依赖，勿破坏）：本类读取 JWT 的 {@code roles} claim 时**不验签**，
 * 依赖 Micronaut SecurityFilter 已在更早相位完成签名校验（伪造签名 JWT 实测 401，
 * 到不了调用方）。若未来把某个调用面改成匿名可达，必须先恢复验签再谈角色。
 *
 * <p>
 * <b>演进缝隙（维护者裁定 2026-09-29）</b>：认证/授权模块不受 fork-only 约束——
 * OSS 的削弱是商业策略而非技术设计。未来若按 OAuth2 规范重构（Kestra 作为
 * resource server 校验第三方 IdP 的 access token：RFC 6750 Bearer / RFC 9068
 * JWT profile / introspection，角色改由 scope 或 {@code authorities} claim 承载），
 * 只需改写本类实现（角色来源与判定逻辑），调用方（filter/`@Secured` 注解层）
 * 与 {@link #ROLE_ADMIN} 语义不变——本类即预留的替换点。
 */
@Singleton
public class DshAdminAuthorizer {

    /** admin 角色字面量（oidc_user.roles 与 JWT roles claim 同名）。 */
    public static final String ROLE_ADMIN = "admin";

    /** JWT cookie 名（与 micronaut-security 的 cookie token reader 同一凭据）。 */
    private static final String JWT_COOKIE = "JWT";

    /**
     * 请求方是否持有 admin 角色。凭据来源：JWT cookie 优先，其次 {@code Authorization:
     * Bearer}（与 SecurityFilter 的凭据读取一致）；两者皆无或解析失败一律 false。
     */
    public boolean isAdmin(HttpRequest<?> request) {
        String token = jwtFromCookie(request);
        if (token == null) {
            token = jwtFromBearerHeader(request);
        }
        if (token == null) {
            return false;
        }
        return hasAdminRole(token);
    }

    private String jwtFromCookie(HttpRequest<?> request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return cookies.findCookie(JWT_COOKIE)
            .map(io.micronaut.http.cookie.Cookie::getValue)
            .orElse(null);
    }

    private String jwtFromBearerHeader(HttpRequest<?> request) {
        return request.getHeaders().getAuthorization()
            .filter(authorization -> authorization.startsWith("Bearer "))
            .map(authorization -> authorization.substring("Bearer ".length()))
            .orElse(null);
    }

    /**
     * 解析 JWT payload 的 {@code roles} claim（不验签——见类注释信任链）。
     * 解析失败按非 admin 处理（fail-closed）。
     */
    static boolean hasAdminRole(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class);
            Object roles = claims.get("roles");
            return roles instanceof List<?> list
                && list.stream().map(String::valueOf).anyMatch(ROLE_ADMIN::equals);
        } catch (Exception e) {
            return false;
        }
    }
}
