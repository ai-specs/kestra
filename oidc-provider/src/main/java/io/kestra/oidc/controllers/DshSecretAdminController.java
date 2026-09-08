package io.kestra.oidc.controllers;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.kestra.core.tenant.TenantService;
import io.kestra.oidc.services.OidcTokenService;
import io.kestra.oidc.services.OidcUserService;
import io.kestra.oidc.services.OidcUserService.OidcUser;
import io.kestra.oidc.services.PostgresSecretStore;

import com.nimbusds.jwt.JWTClaimsSet;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * dsh 托管 secret 管理端点（OSS 叠加层，仿企业版能力）。
 *
 * <p>
 * 与开源 {@code SecretController}（只读列表）共享 {@code /api/v1/{tenant}/secrets} 前缀：
 * 本控制器只提供写操作与 managed 判定，读列表仍由开源端点经叠加的
 * {@link DshSecretService} 合并返回。
 *
 * <p>
 * 授权（对齐 {@code OidcUserAdminController}）：写操作要求 {@code admin} 角色
 * （session cookie / Bearer / JWT cookie 三路径）；{@code GET /managed} 仅要求认证——
 * UI 列表页需要它对全部登录用户做行级只读判定。
 *
 * <p>
 * 隔离：写操作按 (tenant, namespace, key) 精确寻址；读取隔离由
 * {@link PostgresSecretStore#find} 的 namespace 继承链保证。
 */
@Controller("/api/v1/{tenant}/secrets")
@Requires(bean = PostgresSecretStore.class)
@Requires(property = "kestra.encryption.secret-key")
public class DshSecretAdminController {

    private static final String ADMIN_ROLE = "admin";
    /** Secret key 命名：大写字母开头，字母/数字/下划线（对齐 SecretService 大写语义）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Inject
    protected TenantService tenantService;

    @Inject
    protected PostgresSecretStore store;

    @Inject
    protected OidcUserService userService;

    @Inject
    protected OidcTokenService tokenService;

    /** 请求体：value 仅在创建/需要更新值时出现；description 为唯一元数据。 */
    public record SecretWriteRequest(String value, String description) {}

    /**
     * 当前 tenant 全部托管 secret 元数据（DB 管理，UI 行级只读判定与 description 展示用）。
     * 仅需认证。结构：{@code {"secrets":[{"namespace","key","description"}]}}。
     */
    @Get("/managed")
    @ExecuteOn(TaskExecutors.IO)
    @Operation(tags = { "Secrets" }, summary = "List managed (DB-backed) secret metadata")
    public HttpResponse<Map<String, Object>> managedKeys(HttpRequest<?> request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("secrets", store.allManagedMetadata(tenantService.resolveTenant()));
            return HttpResponse.ok(body);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, Map.of(
                "error", "internal_error",
                "error_description", e.getMessage()));
        }
    }

    /** 创建托管 secret。值必填；key 已存在返回 409。 */
    @Post("/{namespace}/{key}")
    @ExecuteOn(TaskExecutors.IO)
    @Operation(tags = { "Secrets" }, summary = "Create a managed secret")
    public HttpResponse<?> create(
        HttpRequest<?> request,
        @PathVariable String namespace,
        @PathVariable String key,
        @Body SecretWriteRequest body
    ) throws IOException {
        requireAdmin(request);
        validateNamespace(namespace);
        validateKey(key);
        if (body == null || body.value() == null || body.value().isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, Map.of(
                "error", "invalid_request",
                "error_description", "value is required to create a secret"));
        }
        String tenantId = tenantService.resolveTenant();
        if (store.exists(tenantId, namespace, key)) {
            throw new HttpStatusException(HttpStatus.CONFLICT, Map.of(
                "error", "conflict",
                "error_description", "Secret '" + key + "' already exists in namespace '" + namespace + "'"));
        }
        store.put(tenantId, namespace, key, body.value(), body.description());
        return HttpResponse.ok(Map.of("namespace", namespace, "key", key));
    }

    /** 更新托管 secret：key 不可改；value 为空 = 仅更新元数据。不存在返回 404。 */
    @Patch("/{namespace}/{key}")
    @ExecuteOn(TaskExecutors.IO)
    @Operation(tags = { "Secrets" }, summary = "Update a managed secret (value null/blank = metadata only, never overwrites with empty)")
    public HttpResponse<?> update(
        HttpRequest<?> request,
        @PathVariable String namespace,
        @PathVariable String key,
        @Body SecretWriteRequest body
    ) throws IOException {
        requireAdmin(request);
        validateNamespace(namespace);
        validateKey(key);
        if (body == null || (body.value() == null && body.description() == null)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, Map.of(
                "error", "invalid_request",
                "error_description", "nothing to update (value/description all empty)"));
        }
        String tenantId = tenantService.resolveTenant();
        if (!store.exists(tenantId, namespace, key)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, Map.of(
                "error", "not_found",
                "error_description", "Secret '" + key + "' not found in namespace '" + namespace + "'"));
        }
        store.update(tenantId, namespace, key, body.value(), body.description());
        return HttpResponse.ok(Map.of("namespace", namespace, "key", key));
    }

    /** 删除托管 secret。不存在返回 404。 */
    @Delete("/{namespace}/{key}")
    @ExecuteOn(TaskExecutors.IO)
    @Operation(tags = { "Secrets" }, summary = "Delete a managed secret")
    public HttpResponse<?> delete(
        HttpRequest<?> request,
        @PathVariable String namespace,
        @PathVariable String key
    ) throws IOException {
        requireAdmin(request);
        validateNamespace(namespace);
        validateKey(key);
        String tenantId = tenantService.resolveTenant();
        if (!store.exists(tenantId, namespace, key)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, Map.of(
                "error", "not_found",
                "error_description", "Secret '" + key + "' not found in namespace '" + namespace + "'"));
        }
        store.delete(tenantId, namespace, key);
        return HttpResponse.noContent();
    }

    // ------------------------------------------------------------------ helpers

    private static void validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, Map.of(
                "error", "invalid_request",
                "error_description", "namespace is required"));
        }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, Map.of(
                "error", "invalid_request",
                "error_description", "Secret key must match [A-Z][A-Z0-9_]*"));
        }
    }

    private void requireAdmin(HttpRequest<?> request) {
        Optional<OidcUser> fromSession = userService.authenticatedUser(request);
        if (fromSession.isPresent()) {
            if (hasRole(fromSession.get().roles(), ADMIN_ROLE)) {
                return;
            }
            throw forbidden();
        }

        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            try {
                JWTClaimsSet claims = tokenService.validateAccessToken(authorization.substring("Bearer ".length()).trim());
                List<String> roles = claims.getStringListClaim("roles") == null
                    ? List.of()
                    : claims.getStringListClaim("roles");
                if (hasRole(roles, ADMIN_ROLE)) {
                    return;
                }
            } catch (Exception e) {
                throw new HttpStatusException(HttpStatus.UNAUTHORIZED, Map.of(
                    "error", "invalid_token",
                    "error_description", e.getMessage()));
            }
            throw forbidden();
        }

        // JWT cookie 路径（Kestra UI 的 axios 请求认证方式）
        var cookie = request.getCookies().findCookie("JWT");
        if (cookie.isPresent() && !cookie.get().getValue().isBlank()) {
            Optional<OidcUser> fromJwt = tokenService.validateSessionJwt(cookie.get().getValue())
                .flatMap(claims -> userService.directoryUser(claims.getSubject()));
            if (fromJwt.isPresent()) {
                if (hasRole(fromJwt.get().roles(), ADMIN_ROLE)) {
                    return;
                }
                throw forbidden();
            }
        }
        throw new HttpStatusException(HttpStatus.UNAUTHORIZED, Map.of(
            "error", "authentication required",
            "error_description", "OIDC session cookie, JWT cookie or Bearer access token required"));
    }

    private static boolean hasRole(List<String> roles, String role) {
        return roles != null && roles.stream().anyMatch(role::equalsIgnoreCase);
    }

    private static HttpStatusException forbidden() {
        return new HttpStatusException(HttpStatus.FORBIDDEN, Map.of(
            "error", "forbidden",
            "error_description", "admin role is required"));
    }
}
