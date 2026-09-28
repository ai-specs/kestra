package io.kestra.webserver.controllers.api;

import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.webserver.services.DshAdminAuthorizer;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
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
 * 本控制器提供面向 UI 的浏览面：员工列表 → 详情（会话表）→ 全量文件树 → 文件内容。
 *
 * <p>
 * 数据层刻意直读物理存储（StorageInterface 的 kestra:///{ns}/_files/ 前缀递归列举），
 * 不经 Namespace 元数据面（namespace_file_metadata）：员工会话文件由共享执行流的
 * UploadFiles 写入，历史上被一次性删除操作标记 deleted 而物理文件仍在，元数据面
 * 已与物理事实脱钩——管理排障面以物理存储为准（与 docker exec ls 看到的一致）。
 *
 * <p>
 * 员工清单从用户目录（oidc_user 表，与 DshMetricsController 同款裸 JDBC）推导：
 * 员工 ns = DshEmployeeNamespace.of(sub)。认证走 Kestra 标准 SecurityFilter（cookie
 * JWT——apps/namespace 文件页同款）；路径刻意避开 /api/v1/dsh/**（那是
 * OidcBearerAuthFilter 的 Bearer 面）。本面只读不写。
 */
@Controller("/api/v1/{tenant}/dsh-employee")
@Secured(DshAdminAuthorizer.ROLE_ADMIN)
@ExecuteOn(TaskExecutors.IO)
@Slf4j
public class DshEmployeeController {

    /** 员工命名空间前缀（与 DshEmployeeNamespace 一致；字面量避免模块耦合）。 */
    private static final String EMPLOYEE_PREFIX = "employee.";

    /** 单文件读取上限（排障用途；reply 同上限）。 */
    private static final int MAX_FILE_BYTES = 64 * 1024;

    @Inject
    private StorageInterface storageInterface;

    @Inject
    private TenantService tenantService;

    @Inject
    private DshMetricsConfiguration metricsConfiguration;

    /** 列出全部员工命名空间（用户目录推导 + 存储非空过滤）+ 会话统计。 */
    @Get(uri = "/list")
    @Operation(summary = "List dsh employee namespaces with session stats (Kestra UI employee page)")
    public HttpResponse<List<Map<String, Object>>> list() {
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

    /** 员工详情（详情页头部：sub 反查 + 会话统计）。 */
    @Get(uri = "/{namespace}")
    @Operation(summary = "Employee namespace details (sub, session count)")
    public HttpResponse<Map<String, Object>> detail(String namespace) {
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        for (String sub : activeHumanUsers()) {
            if (DshEmployeeNamespace.of(sub).equals(namespace)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("namespace", namespace);
                result.put("sub", sub);
                String tenant = tenantService.resolveTenant();
                try {
                    result.put("sessionCount", listSessions(tenant, namespace).size());
                } catch (Exception e) {
                    log.debug("dsh-employee: listing sessions failed for {} (treated as empty): {}", namespace, e.getMessage());
                    result.put("sessionCount", 0);
                }
                return HttpResponse.ok(result);
            }
        }
        return HttpResponse.notFound(Map.of("error", "unknown employee namespace"));
    }

    /** 某员工命名空间的会话列表（一次全量列举聚合：文件数 / 上传 / 回复 / 错误日志标记）。 */
    @Get(uri = "/{namespace}/sessions")
    @Operation(summary = "List sessions under one employee namespace")
    public HttpResponse<?> sessions(String namespace) {
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        String tenant = tenantService.resolveTenant();
        try {
            // sessionKey -> [fileCount, hasUploads, hasReply, hasStderr]（一次 all() 聚合，零逐文件探测）
            Map<String, Map<String, Object>> bySession = new LinkedHashMap<>();
            for (String p : allFilePaths(tenant, namespace)) {
                if (!p.startsWith("sessions/") || p.length() <= "sessions/".length()) {
                    continue;
                }
                String rest = p.substring("sessions/".length());
                String sessionId = rest.split("/")[0];
                Map<String, Object> row = bySession.computeIfAbsent(sessionId, id -> {
                    Map<String, Object> fresh = new LinkedHashMap<>();
                    fresh.put("sessionId", id);
                    fresh.put("fileCount", 0);
                    fresh.put("hasUploads", false);
                    fresh.put("hasReply", false);
                    fresh.put("hasStderr", false);
                    return fresh;
                });
                row.merge("fileCount", 1, (a, b) -> (Integer) a + (Integer) b);
                if (rest.startsWith(sessionId + "/uploads/")) {
                    row.put("hasUploads", true);
                } else if (rest.equals(sessionId + "/reply.txt")) {
                    row.put("hasReply", true);
                } else if (rest.equals(sessionId + "/stderr.log")) {
                    row.put("hasStderr", true);
                }
            }
            return HttpResponse.ok(new ArrayList<>(bySession.values()));
        } catch (Exception e) {
            log.warn("dsh-employee sessions failed for {}", namespace, e);
            return HttpResponse.serverError(Map.of("error", "storage listing failed"));
        }
    }

    /** 命名空间全量文件树（物理存储递归列举：path/size/directory，供文件浏览 tab 建树）。 */
    @Get(uri = "/{namespace}/tree")
    @Operation(summary = "List all physical files under one employee namespace (raw storage, admin-only)")
    public HttpResponse<?> tree(String namespace) {
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        String tenant = tenantService.resolveTenant();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            collectTree(tenant, namespace, "", result, 0);
            return HttpResponse.ok(result);
        } catch (Exception e) {
            log.warn("dsh-employee tree failed for {}", namespace, e);
            return HttpResponse.serverError(Map.of("error", "storage listing failed"));
        }
    }

    /** 读取员工命名空间内的小文本文件（物理存储直读；尾部截 64KB；二进制返回标记）。 */
    @Get(uri = "/{namespace}/file")
    @Operation(summary = "Read one file under an employee namespace (raw storage, tail-capped)")
    public HttpResponse<?> file(
        String namespace,
        @QueryValue String path
    ) {
        HttpResponse<?> guard = fileGuard(namespace, path);
        if (guard != null) {
            return guard;
        }
        String normalized = normalizePath(path);
        String tenant = tenantService.resolveTenant();
        String relative = normalized.substring(1);
        try {
            // stat 在 get 之前：目录路径直接 400，不先开流（存储层对目录 get 会抛 404）
            io.kestra.core.storages.FileAttributes attr = statOrNull(tenant, namespace, storageUri(namespace, relative));
            if (attr != null && safeIsDirectory(attr)) {
                return HttpResponse.badRequest(Map.of("error", "path is a directory"));
            }
            java.io.InputStream in = storageInterface.get(tenant, namespace, storageUri(namespace, relative));
            if (in == null) {
                return HttpResponse.notFound(Map.of("error", "file not found"));
            }
            byte[] tail;
            // 元数据可用时按 size 跳读尾部，超限文件不整读进内存（防御 OOM）；
            // stat 失败（畸形条目等）退回整读——预览排障面优先可用性。
            // truncated 以 stat 的 size 为准（skip 后恰好剩 MAX 字节，读长度判不出超限）
            long size = attr == null ? -1 : safeSize(attr);
            boolean truncated = size > MAX_FILE_BYTES;
            if (truncated) {
                try (java.io.InputStream is = in) {
                    long toSkip = size - MAX_FILE_BYTES;
                    while (toSkip > 0) {
                        long skipped = is.skip(toSkip);
                        if (skipped <= 0) {
                            break;
                        }
                        toSkip -= skipped;
                    }
                    byte[] read = is.readNBytes(MAX_FILE_BYTES + 1);
                    tail = read.length > MAX_FILE_BYTES
                        ? java.util.Arrays.copyOfRange(read, 0, MAX_FILE_BYTES)
                        : read;
                }
            } else {
                try (java.io.InputStream is = in) {
                    tail = is.readAllBytes();
                }
                truncated = tail.length > MAX_FILE_BYTES;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", normalized);
            result.put("size", Math.max(size, tail.length));
            result.put("truncated", truncated);
            if (isMostlyText(tail)) {
                result.put("binary", false);
                result.put("content", new String(tail, StandardCharsets.UTF_8));
            } else {
                result.put("binary", true);
            }
            return HttpResponse.ok(result);
        } catch (java.io.FileNotFoundException e) {
            return HttpResponse.notFound(Map.of("error", "file not found"));
        } catch (Exception e) {
            log.warn("dsh-employee file read failed {} {}", namespace, normalized, e);
            return HttpResponse.serverError(Map.of("error", "file read failed"));
        }
    }

    /** 全量流式下载（不截断）：zstd/图片等二进制排障素材落地分析。 */
    @Get(uri = "/{namespace}/file/download")
    @Operation(summary = "Download one file under an employee namespace (raw storage, full stream)")
    public HttpResponse<?> download(
        String namespace,
        @QueryValue String path
    ) {
        HttpResponse<?> guard = fileGuard(namespace, path);
        if (guard != null) {
            return guard;
        }
        String normalized = normalizePath(path);
        String tenant = tenantService.resolveTenant();
        String relative = normalized.substring(1);
        try {
            // stat 在 get 之前：目录路径直接 400，不先开流（存储层对目录 get 会抛 404）
            io.kestra.core.storages.FileAttributes attr = statOrNull(tenant, namespace, storageUri(namespace, relative));
            if (attr != null && safeIsDirectory(attr)) {
                return HttpResponse.badRequest(Map.of("error", "path is a directory"));
            }
            java.io.InputStream in = storageInterface.get(tenant, namespace, storageUri(namespace, relative));
            if (in == null) {
                return HttpResponse.notFound(Map.of("error", "file not found"));
            }
            // filename 双写：ASCII 回退名 + RFC 5987 UTF-8（畸形文件名也能正确落盘）
            String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
            String asciiFallback = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
            String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            // 上游同款 no-cache；stat 可用时带上长度/最后修改时间（浏览器可显示进度）
            io.micronaut.http.server.types.files.StreamedFile streamed = attr == null
                ? new io.micronaut.http.server.types.files.StreamedFile(in, MediaType.APPLICATION_OCTET_STREAM_TYPE)
                : new io.micronaut.http.server.types.files.StreamedFile(
                    in, MediaType.APPLICATION_OCTET_STREAM_TYPE, attr.getLastModifiedTime(), safeSize(attr));
            return HttpResponse.ok(streamed)
                .header("Content-Disposition", "attachment; filename=\"" + asciiFallback
                    + "\"; filename*=UTF-8''" + encoded)
                .header(io.micronaut.http.HttpHeaders.CACHE_CONTROL, "no-cache");
        } catch (java.io.FileNotFoundException e) {
            return HttpResponse.notFound(Map.of("error", "file not found"));
        } catch (Exception e) {
            log.warn("dsh-employee file download failed {} {}", namespace, normalized, e);
            return HttpResponse.serverError(Map.of("error", "file download failed"));
        }
    }

    /** stat 元数据：不存在/读取失败（畸形条目等）返回 null，由调用方按可用性降级。 */
    private io.kestra.core.storages.FileAttributes statOrNull(String tenant, String namespace, java.net.URI uri) {
        try {
            return storageInterface.getAttributes(tenant, namespace, uri);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * file/download 共用的入参守卫（employee 前缀 + path 归一），非空即拒绝响应。
     * 角色控制不在本层——类级 {@code @Secured(DshAdminAuthorizer.ROLE_ADMIN)} 已在
     * SecurityFilter 相位拒绝非 admin（框架原生，先于路由调用）。
     */
    private HttpResponse<?> fileGuard(String namespace, String path) {
        if (!namespace.startsWith(EMPLOYEE_PREFIX)) {
            return HttpResponse.badRequest(Map.of("error", "not an employee namespace"));
        }
        if (normalizePath(path) == null) {
            return HttpResponse.badRequest(Map.of("error", "invalid path"));
        }
        return null;
    }

    /**
     * 单条目目录判定（防畸形条目炸整棵树）：LocalFileAttributes.getType() 对
     * 非文件非目录（符号链接等）直接抛 RuntimeException——排障浏览面的原则是
     * 「父目录下有什么就显示什么」，未知类型按普通文件展示、不中断列举。
     */
    private static boolean safeIsDirectory(io.kestra.core.storages.FileAttributes attr) {
        try {
            return attr.getType() == io.kestra.core.storages.FileAttributes.FileType.Directory;
        } catch (Exception e) {
            return false;
        }
    }

    private static long safeSize(io.kestra.core.storages.FileAttributes attr) {
        try {
            return attr.getSize();
        } catch (Exception e) {
            return 0;
        }
    }

    private void collectTree(String tenant, String namespace, String dirPath, List<Map<String, Object>> out, int depth) throws java.io.IOException {
        if (depth > MAX_LIST_DEPTH) {
            return;
        }
        for (io.kestra.core.storages.FileAttributes attr : storageInterface.list(tenant, namespace, storageUri(namespace, dirPath))) {
            String child = dirPath + attr.getFileName();
            boolean isDir = safeIsDirectory(attr);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", child);
            row.put("size", safeSize(attr));
            row.put("directory", isDir);
            out.add(row);
            if (isDir) {
                collectTree(tenant, namespace, child + "/", out, depth + 1);
            }
        }
    }

    /** 前 2KB 内出现 NUL 或三成以上非文本字节即按二进制处理（zstd/图片等不做文本预览）。 */
    private static boolean isMostlyText(byte[] sample) {
        if (sample.length == 0) {
            return true;
        }
        int check = Math.min(sample.length, 2048);
        int suspicious = 0;
        for (int i = 0; i < check; i++) {
            byte b = sample[i];
            if (b == 0) {
                return false;
            }
            if ((b < 0x20 || b == 0x7F) && b != '\n' && b != '\r' && b != '\t') {
                suspicious++;
            }
        }
        return suspicious * 10 < check;
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
        return allFilePaths(tenant, namespace).stream()
            .filter(p -> p.startsWith("sessions/") && p.length() > "sessions/".length())
            .map(p -> p.substring("sessions/".length()))
            .map(rest -> rest.split("/")[0])
            .distinct()
            .sorted()
            .toList();
    }

    /**
     * 命名空间下全部物理文件路径（归一去前导斜杠）。
     *
     * <p>刻意绕过 Namespace 元数据面（InternalNamespace.all() 读 namespace_file_metadata，
     * 只反映 last=true & deleted=false 的登记）：员工会话文件由共享执行流的
     * UploadFiles 写入，历史上被一次性删除操作标记过 deleted 而物理文件仍在——
     * 管理排障面以物理存储为准（与 docker exec ls 看到的一致），直接递归列举
     * StorageInterface 的 kestra:///{ns}/_files/ 前缀。
     */
    private List<String> allFilePaths(String tenant, String namespace) throws java.io.IOException {
        List<String> paths = new ArrayList<>();
        collectFiles(tenant, namespace, "", paths, 0);
        return paths;
    }

    /** 深度防御：递归上限（防恶意构造的深层目录把请求挂死）。 */
    private static final int MAX_LIST_DEPTH = 32;

    private void collectFiles(String tenant, String namespace, String dirPath, List<String> out, int depth) throws java.io.IOException {
        if (depth > MAX_LIST_DEPTH) {
            return;
        }
        for (io.kestra.core.storages.FileAttributes attr : storageInterface.list(tenant, namespace, storageUri(namespace, dirPath))) {
            String child = dirPath + attr.getFileName();
            if (safeIsDirectory(attr)) {
                collectFiles(tenant, namespace, child + "/", out, depth + 1);
            } else {
                out.add(child);
            }
        }
    }

    /**
     * 员工命名空间文件的物理存储 URI：kestra:///{ns 点转斜杠}/_files/{path}。
     * 用多参数 URI 构造器（与上游 NamespaceFile.of 同款）：path 中的 URI 非法字符
     * （空格、`#`、`%` 等——畸形文件名排障也要能看）按百分号编码传入，而不是被
     * URI.create 当 fragment/截断。
     *
     * <p>
     * 显式边界断言（纵深防御）：可达范围被钉死在 {@code /employee/**} 下且路径中
     * 不得出现 {@code ..} 段——normalizePath 已在上游保证，这里防未来重构无意
     * 破坏边界（违反即 IllegalArgumentException，视为代码缺陷而非用户输入问题）。
     */
    static java.net.URI storageUri(String namespace, String filePath) {
        String path = "/" + namespace.replace(".", "/") + "/_files/" + filePath;
        if (!path.startsWith("/employee/") || path.contains("/../") || path.endsWith("/..")
            || path.contains("\\")) {
            throw new IllegalArgumentException("path escapes employee namespace storage: " + filePath);
        }
        try {
            return new java.net.URI("kestra", "", path, null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid namespace file path: " + filePath, e);
        }
    }

    /**
     * 归一并校验用户传入的文件路径（带前导斜杠），非法返回 null：
     * 折叠重复斜杠；穿越按「段」判（{@code ..}/{@code .} 段拒绝，a..b.txt 合法）；
     * 反斜杠与 ISO 控制字符拒绝。配合 {@link #storageUri} 的边界断言构成双防线。
     */
    static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.startsWith("/") ? raw : "/" + raw;
        // 归一重复前导斜杠（"//sessions/..." 双拼容错），内部 "//" 属畸形路径一并折叠
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return null;
            }
        }
        if (value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return value;
    }
}
