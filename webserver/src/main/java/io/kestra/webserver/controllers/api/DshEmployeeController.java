package io.kestra.webserver.controllers.api;

import io.kestra.core.storages.Namespace;
import io.kestra.core.storages.NamespaceFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dsh 员工命名空间管理面（Kestra UI Resources→employee 页，2026-09-28）：
 * 云电脑模式的员工命名空间（employee.{slug}-{hash8}）只有文件、没有 flow——上游
 * OSS 的 namespace 列表从 flow 推导，因此这些命名空间不出现在 /ui/main/namespaces。
 * 本控制器提供面向 UI 的浏览面：员工列表（含会话统计）→ 会话列表 → 文件内容。
 *
 * <p>
 * 员工清单从用户目录（oidc_user 表，与 DshMetricsController 同款裸 JDBC）推导：
 * 员工 ns = DshEmployeeNamespace.of(sub)，凡存储中实际有文件的才列出。认证走
 * Kestra 标准 SecurityFilter（cookie JWT——apps/namespace 文件页同款）；路径刻意
 * 避开 /api/v1/dsh/**（那是 OidcBearerAuthFilter 的 Bearer 面）。本面只读不写。
 */
@Controller("/api/v1/{tenant}/dsh-employee")
@ExecuteOn(TaskExecutors.IO)
@Slf4j
public class DshEmployeeController {

    /** 员工命名空间前缀（与 DshEmployeeNamespace 一致；字面量避免模块耦合）。 */
    private static final String EMPLOYEE_PREFIX = "employee.";

    /** 单文件读取上限（排障用途；reply 同上限）。 */
    private static final int MAX_FILE_BYTES = 64 * 1024;

    @Inject
    private NamespaceFactory namespaceFactory;

    @Inject
    private StorageInterface storageInterface;

    @Inject
    private TenantService tenantService;

    @Inject
    private DshMetricsConfiguration metricsConfiguration;

    /** 列出全部员工命名空间（用户目录推导 + 存储非空过滤）+ 会话统计。 */
    @Get(uri = "/list")
    @Operation(summary = "List dsh employee namespaces with session stats (Kestra UI employee page)")
    public HttpResponse<List<Map<String, Object>>> list(HttpRequest<?> request) {
        if (!isAdminCaller(request)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(List.of(Map.of("error", "admin role is required")));
        }
        String tenant = tenantService.resolveTenant();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sub : activeHumanUsers()) {
            String namespace = DshEmployeeNamespace.of(sub);
            List<String> sessions;
            try {
                sessions = listSessions(tenant, namespace);
            } catch (Exception e) {
                // 空/新建命名空间可能因元数据缺失异常——按零会话列出而非跳过（管理员全景）
                log.debug("dsh-employee: listing sessions failed for {} (treated as empty): {}", namespace, e.getMessage());
                sessions = List.of();
            }
            // 用户裁定：管理员全景——不按存储过滤
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("namespace", namespace);
            row.put("sub", sub);
            row.put("sessionCount", sessions.size());
            result.add(row);
        }
        return HttpResponse.ok(result);
    }

    /** 某员工命名空间的会话列表（sessionId + 是否有回复/错误日志）。 */
    @Get(uri = "/{namespace}/sessions")
    @Operation(summary = "List sessions under one employee namespace")
    public HttpResponse<?> sessions(HttpRequest<?> request, String namespace) {
        if (!isAdminCaller(request)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(List.of(Map.of("error", "admin role is required")));
        }
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        String tenant = tenantService.resolveTenant();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String sessionId : listSessions(tenant, namespace)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", sessionId);
                row.put("hasReply", exists(tenant, namespace, "/sessions/" + sessionId + "/reply.txt"));
                row.put("hasStderr", exists(tenant, namespace, "/sessions/" + sessionId + "/stderr.log"));
                result.add(row);
            }
            return HttpResponse.ok(result);
        } catch (Exception e) {
            log.warn("dsh-employee sessions failed for {}", namespace, e);
            return HttpResponse.serverError(Map.of("error", "storage listing failed"));
        }
    }

    /** 读取员工命名空间内的小文本文件（reply/stderr/上传素材预览；尾部截 64KB）。 */
    @Get(uri = "/{namespace}/file")
    @Operation(summary = "Read one text file under an employee namespace (tail-capped, for troubleshooting)")
    public HttpResponse<Map<String, Object>> file(
        HttpRequest<?> request,
        String namespace,
        @QueryValue String path
    ) {
        if (!isAdminCaller(request)) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of("error", "admin role is required"));
        }
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        String normalized = normalizePath(path);
        if (normalized == null) {
            return HttpResponse.badRequest(Map.of("error", "invalid path"));
        }
        String tenant = tenantService.resolveTenant();
        Namespace ns = namespaceFactory.of(tenant, namespace, storageInterface);
        Path p = Path.of(normalized);
        try {
            if (!ns.exists(p)) {
                return HttpResponse.notFound(Map.of("error", "file not found"));
            }
            try (InputStream in = ns.getFileContent(p)) {
                byte[] all = in.readAllBytes();
                int from = Math.max(0, all.length - MAX_FILE_BYTES);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", normalized);
                result.put("size", all.length);
                result.put("truncated", all.length > MAX_FILE_BYTES);
                result.put("content", new String(all, from, all.length - from, StandardCharsets.UTF_8));
                return HttpResponse.ok(result);
            }
        } catch (Exception e) {
            log.warn("dsh-employee file read failed {} {}", namespace, normalized, e);
            return HttpResponse.serverError(Map.of("error", "file read failed"));
        }
    }


    /** Parses the JWT cookie payload (middle segment, base64url) to read sub/roles claims. */
    private static java.util.Map<String, Object> jwtPayload(HttpRequest<?> request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Map.of();
        }
        var jwt = cookies.findCookie(io.kestra.webserver.services.BasicAuthService.BASIC_AUTH_COOKIE_NAME)
            .or(() -> cookies.findCookie("JWT"));
        if (jwt.isEmpty()) {
            return Map.of();
        }
        try {
            String[] parts = jwt.get().getValue().split("\\.");
            if (parts.length != 3) {
                return Map.of();
            }
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 用户裁定（2026-09-28）：此页为管理员专属——非 admin 一律 403。 */
    private static boolean isAdminCaller(HttpRequest<?> request) {
        var claims = jwtPayload(request);
        Object roles = claims.get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(String::valueOf).anyMatch("admin"::equals);
        }
        return false;
    }

    private List<String> activeHumanUsers() {
        List<String> subs = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
            "SELECT username FROM oidc_user WHERE user_state = 'ACTIVE' AND (type IS NULL OR type = 'human')")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    subs.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.warn("dsh-employee user listing failed", e);
        }
        return subs;
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(metricsConfiguration.jdbcUrl(), metricsConfiguration.username(), metricsConfiguration.password());
    }

    private List<String> listSessions(String tenant, String namespace) throws java.io.IOException {
        return namespaceFactory.of(tenant, namespace, storageInterface).all().stream()
            .map(f -> f.filePath().toString())
            // NamespaceFile.filePath() 带前导斜杠（/sessions/...）——归一后取一级目录
            .map(p -> p.startsWith("/") ? p.substring(1) : p)
            .filter(p -> p.startsWith("sessions/") && p.length() > "sessions/".length())
            .map(p -> p.substring("sessions/".length()))
            .map(rest -> rest.split("/")[0])
            .distinct()
            .sorted()
            .toList();
    }

    private boolean hasAnyFile(String tenant, String namespace) {
        try {
            return !namespaceFactory.of(tenant, namespace, storageInterface).all().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean exists(String tenant, String namespace, String path) {
        try {
            return namespaceFactory.of(tenant, namespace, storageInterface).exists(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.startsWith("/") ? raw : "/" + raw;
        if (value.contains("..") || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return value;
    }
}
