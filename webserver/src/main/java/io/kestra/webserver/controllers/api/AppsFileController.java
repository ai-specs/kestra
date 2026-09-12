package io.kestra.webserver.controllers.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.models.namespaces.files.NamespaceFileMetadata;
import io.kestra.core.storages.Namespace;
import io.kestra.core.storages.NamespaceFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.webserver.configuration.AppsFilesConfiguration;
import io.kestra.webserver.services.AppRouteRegistry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * dsh Apps 统一文件端点（设计文档 {@code docs/dsh-apps-amis-editor.md} §6.1）——
 * 编辑器与 flow/trigger 完全解耦，全部输入 = 约定路径 + 目录扫描。
 *
 * <ul>
 *   <li>{@code GET /api/v1/apps/files?path=apps/{appName}/{...}.json} — 按约定路径读页面 schema。</li>
 *   <li>{@code PUT /api/v1/apps/files?path=apps/{appName}/{...}.json} — 按约定路径写页面 schema
 *       （文件不存在则创建；body 必须是 JSON 对象，否则 400）。</li>
 *   <li>{@code GET /api/v1/apps/pages} — 目录扫描约定根 {@code apps/}，返回页面树
 *       （一级 = App，二级/三级 = 文件/子目录层级，同名文件 + 目录合并为同一菜单节点）。</li>
 * </ul>
 *
 * <p>约定与硬约束：路径各段仅 {@code [A-Za-z0-9_-]}、最终 resolve 后必须落在
 * {@code apps/{appName}/} 内（防路径穿越）；{@code apps/designer/} 为保留前缀（设计器
 * HTML 入口占用），GET/PUT 一律 400、目录树跳过；namespace 绑定由
 * {@link AppsFilesConfiguration#getRootNamespace()} 决定（默认 {@code dsh.apps}），
 * 目录树构建时与 AppRouteRegistry 中 flow 的 namespace 交叉比对，不一致的 App 携带
 * {@code warning} 字段（不静默）。
 *
 * <p>认证与 CSRF 与现有 {@code /api/v1/**} 完全一致（OIDC 登录；PUT 走
 * CsrfTokenFilter 强制 X-CSRF-TOKEN）。授权边界：任何登录用户可读写约定根目录内任意
 * 文件，权限面与现有全部 /api/v1/** 端点等同（docs/dsh-apps-amis.md §1）。
 */
@Controller("/api/v1/apps")
@Singleton
@ExecuteOn(TaskExecutors.IO)
@Slf4j
public class AppsFileController {

    /** 约定根目录（namespace files 下）。 */
    public static final String CONVENTION_ROOT = "apps";
    /** 保留 appName：/apps/designer 是设计器 HTML 入口。 */
    public static final String RESERVED_DESIGNER = "designer";


    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern JSON_FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.json");

    @Inject
    private TenantService tenantService;

    @Inject
    private NamespaceFactory namespaceFactory;

    @Inject
    private StorageInterface storageInterface;

    @Inject
    private AppsFilesConfiguration appsFiles;

    @Inject
    private ObjectMapper objectMapper;

    /**
     * GET /api/v1/apps/files?path=dsh.apps/apps/{appName}/{...}.json — 读约定路径的页面 schema。
     * path 第一个段是 namespace（必须等于 apps.files.root-namespace，默认 dsh.apps），其余为约定
     * 路径；兼容旧格式 path=apps/{appName}/{...}.json（namespace 缺省用约定根）。
     * 返回前校验最外层 type 为 amis schema 合法枚举——非页面 JSON（配置/数据/敏感文件）拒绝返回（404，与不存在同响应，防探测）。
     */
    @Get(uri = "/files")
    @Operation(summary = "Read an apps convention page schema file, or list it when path ends with '/'")
    public HttpResponse<?> file(@QueryValue String path) {
            String tenant = tenantService.resolveTenant();
            // 目录列举（path 以 / 结尾）：只返回该目录下的页面 json 文件相对路径（文件名清单，
            // 无内容、无全局树——范围由查询参数显式限定，替代已废弃的 /apps/pages 全局树端点）。
            if (path != null && path.endsWith("/")) {
                return listDir(tenant, path);
            }
            Path filePath = validatePathWithNamespace(path);
            try {
                Namespace ns = namespace(tenant);
                try (InputStream in = ns.getFileContent(filePath)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    validateAmisRootType(content, path);
                    return HttpResponse.ok(content).contentType(MediaType.APPLICATION_JSON_TYPE);
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof HttpStatusException) {
                    throw (HttpStatusException) e;
                }
                throw new NotFoundResponseException();
            }
    }

    /**
     * PUT /api/v1/apps/files?path=dsh.apps/apps/{appName}/{...}.json — 写约定路径的页面 schema。
     * 文件不存在则创建（新页面首次保存即建文件）；body 必须是 JSON 对象（防坏写入白屏）。
     */
    @Put(uri = "/files", consumes = MediaType.APPLICATION_JSON)
    @Operation(summary = "Write an apps convention page schema file")
    public HttpResponse<String> putFile(@QueryValue String path, @Body String body) {
        String tenant = tenantService.resolveTenant();
        Path filePath = validatePathWithNamespace(path);
        validateJsonObject(body, path);
        try {
            namespace(tenant).putFile(filePath,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                Namespace.Conflicts.OVERWRITE);
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to write apps page file");
        }
        return HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    /** 顶层 schema 的合法 amis 组件 type（页面文件最外层枚举；非此集合 → 拒绝返回防敏感文件暴露）。 */
    private static final Pattern AMIS_ROOT_TYPE = Pattern.compile(
        "^(app|page|form|wizard|crud|crud2|service|html|container|flex|grid|grid-2d|panel|tabs|steps|"
            + "table|table2|list|cards|chart|chart2|iframe|dialog|drawer|action|button|button-group|"
            + "button-toolbar|divider|anchor|custom|static|collapse|each|fieldset|fieldSet|icon|image|"
            + "images|link|mapping|nav|pagination|qrcode|rating|spinner|switch|tag|textarea|tree|wrapper|"
            + "alert|audio|video|carousel|dropdown-button|group|remark|repeat|uuid|verification-code|"
            + "web-component|input-[a-z0-9-]+)$");

    /** JSON 最外层必须有 "type" 且为 amis 合法枚举；无 type 或值不符 → 404（与文件不存在同响应，防探测）。 */
    private void validateAmisRootType(String content, String path) {
        String type;
        try {
            JsonNode root = objectMapper.readTree(content);
            type = root != null && root.isObject() && root.hasNonNull("type")
                ? root.get("type").asText("")
                : "";
        } catch (IOException e) {
            throw new NotFoundResponseException();
        }
        if (type.isBlank() || !AMIS_ROOT_TYPE.matcher(type).matches()) {
            throw new NotFoundResponseException();
        }
    }

    /**
     * path 语义：{namespace}/{conventionPath}，如 dsh.apps/apps/hello/index.json。
     * 第一段必须等于约定根 namespace（拒绝跨 namespace 读任意文件）；旧格式
     * apps/{...}（无 namespace 段）兼容为约定根。返回约定路径（相对约定根，apps/ 开头）。
     */
    private Path validatePathWithNamespace(String path) {
        if (path == null || path.isBlank()) {
            throw new NotFoundResponseException();
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        if (slash <= 0) {
            throw new NotFoundResponseException();
        }
        String ns = p.substring(0, slash);
        String rest = p.substring(slash + 1);
        String conventionPath;
        if (ns.equals(appsFiles.getRootNamespace())) {
            conventionPath = rest;
        } else if (ns.equals(CONVENTION_ROOT)) {
            // legacy: path=apps/{appName}/{...}.json (namespace omitted → root)
            conventionPath = p;
        } else {
            throw new NotFoundResponseException();
        }
        return validateConventionPath(conventionPath);
    }

    /** 目录列举：path={ns}/apps/{app}/ → 该 app 下全部页面 json 的相对路径（排序）。 */
    private HttpResponse<List<String>> listDir(String tenant, String path) {
        Path dir = validateDirPath(path);
        Namespace ns = namespace(tenant);
        List<String> files = new ArrayList<>();
        try {
            for (NamespaceFileMetadata m : ns.children("/" + dir.toString(), true)) {
                String rel = m.getPath();
                if (rel.startsWith("/")) {
                    rel = rel.substring(1);
                }
                if (rel.endsWith(".json")) {
                    files.add(rel);
                }
            }
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to list app pages: " + e.getMessage());
        }
        Collections.sort(files);
        return HttpResponse.ok(files).contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    /** 目录校验：{ns}/apps/{app}/（与 validatePathWithNamespace 同规则，但允许目录形态）。 */
    private Path validateDirPath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (!trimmed.endsWith("/") || trimmed.length() < 2) {
            throw new NotFoundResponseException();
        }
        return validatePathWithNamespace(trimmed.substring(0, trimmed.length() - 1) + "/__dir__.json")
            .getParent();
    }

    // ───────────────────────── helpers（静态可测） ─────────────────────────

    /**
     * 校验约定路径：必须位于 {@code apps/{appName}/...} 下；各段仅 [A-Za-z0-9_-]；
     * 最后一段必须是 {@code *.json}；resolve 后仍以 {@code apps/} 开头（防穿越）；
     * {@code apps/designer/} 保留前缀拒绝（400）。返回相对约定根根部的 path
     * （如 {@code apps/hello/index.json}）。
     */
    static Path validateConventionPath(String path) {
        if (path == null || path.isBlank()) {
            throw new NotFoundResponseException();
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.startsWith(CONVENTION_ROOT) || (!normalized.equals(CONVENTION_ROOT) && !normalized.startsWith(CONVENTION_ROOT + "/"))) {
            throw new NotFoundResponseException();
        }
        Path p;
        try {
            p = Path.of(normalized).normalize();
        } catch (InvalidPathException e) {
            throw new NotFoundResponseException();
        }
        if (!p.startsWith(CONVENTION_ROOT) || p.getNameCount() < 3) {
            throw new NotFoundResponseException();
        }
        // getName(0) = "apps"（约定根）；appName 是第 1 段。
        String appName = p.getName(1).toString();
        if (!SEGMENT.matcher(appName).matches() || RESERVED_DESIGNER.equals(appName)) {
            throw new NotFoundResponseException();
        }
        for (int i = 2; i < p.getNameCount(); i++) {
            String seg = p.getName(i).toString();
            if (i == p.getNameCount() - 1) {
                if (!JSON_FILE_NAME.matcher(seg).matches()) {
                    throw new NotFoundResponseException();
                }
            } else if (!SEGMENT.matcher(seg).matches()) {
                throw new NotFoundResponseException();
            }
        }
        return p;
    }

    /** body 必须是合法 JSON 对象，否则 404（与文件不存在同响应，防探测）。 */
    private void validateJsonObject(String body, String path) {
        if (body == null || body.isBlank()) {
            throw new NotFoundResponseException();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new NotFoundResponseException();
            }
        } catch (JsonProcessingException e) {
            throw new NotFoundResponseException();
        }
    }

    


    private Namespace namespace(String tenant) {
        return namespaceFactory.of(tenant, appsFiles.getRootNamespace(), storageInterface);
    }

    

}
