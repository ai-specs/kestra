package io.kestra.webserver.controllers.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private AppRouteRegistry routeRegistry;

    @Inject
    private ObjectMapper objectMapper;

    /**
     * GET /api/v1/apps/files?path=dsh.apps/apps/{appName}/{...}.json — 读约定路径的页面 schema。
     * path 第一个段是 namespace（必须等于 apps.files.root-namespace，默认 dsh.apps），其余为约定
     * 路径；兼容旧格式 path=apps/{appName}/{...}.json（namespace 缺省用约定根）。
     * 返回前校验最外层 type 为 amis schema 合法枚举——非页面 JSON（配置/数据/敏感文件）拒绝返回。
     */
    @Get(uri = "/files")
    @Operation(summary = "Read an apps convention page schema file")
    public HttpResponse<String> file(@QueryValue String path) {
        String tenant = tenantService.resolveTenant();
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
            throw new HttpStatusException(HttpStatus.NOT_FOUND,
                "Apps page file not found: " + path + " (" + e.getMessage() + ")");
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
                "Unable to write apps page file " + path + ": " + e.getMessage());
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

    /** JSON 最外层必须有 "type" 且为 amis 合法枚举；无 type 或值不符 → 400（防止意外暴露敏感文件）。 */
    private void validateAmisRootType(String content, String path) {
        String type;
        try {
            JsonNode root = objectMapper.readTree(content);
            type = root != null && root.isObject() && root.hasNonNull("type")
                ? root.get("type").asText("")
                : "";
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "Apps page file is not valid JSON: " + path);
        }
        if (type.isBlank() || !AMIS_ROOT_TYPE.matcher(type).matches()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "Apps page file " + path + " is not an amis page schema: top-level \"type\" must be one of "
                    + "the amis component types (app/page/form/crud/service/input-*...), got '"
                    + (type.isBlank() ? "<missing>" : type) + "'");
        }
    }

    /**
     * path 语义：{namespace}/{conventionPath}，如 dsh.apps/apps/hello/index.json。
     * 第一段必须等于约定根 namespace（拒绝跨 namespace 读任意文件）；旧格式
     * apps/{...}（无 namespace 段）兼容为约定根。返回约定路径（相对约定根，apps/ 开头）。
     */
    private Path validatePathWithNamespace(String path) {
        if (path == null || path.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "path query parameter is required");
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        if (slash <= 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "path must be {namespace}/" + CONVENTION_ROOT + "/{appName}/{page}.json (got " + path + ")");
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
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "path namespace must equal apps.files.root-namespace '" + appsFiles.getRootNamespace()
                    + "' (got '" + ns + "')");
        }
        return validateConventionPath(conventionPath);
    }

    /**
     * GET /api/v1/apps/pages — 目录扫描约定根，返回页面树。不查 flow、不查 trigger。
     */
    @Get(uri = "/pages")
    @Operation(summary = "List the apps page tree (convention directory scan)")
    public List<Map<String, Object>> pages() {
        String tenant = tenantService.resolveTenant();
        Namespace ns = namespace(tenant);
        List<NamespaceFileMetadata> all;
        try {
            all = ns.children("/" + CONVENTION_ROOT, true);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to list apps pages: " + e.getMessage());
        }

        // 注册表 flow namespace（P0 告警：flow 与约定根不同 namespace → 保存/渲染两个文件）。
        Map<String, String> flowNamespaces = new TreeMap<>();
        for (AppRouteRegistry.AppSummary s : routeRegistry.apps(tenant)) {
            flowNamespaces.putIfAbsent(s.appName(), s.namespace());
        }

        // 按一级 App 目录分组（跳过保留名 designer；一级目录本身不作为节点）。
        Map<String, List<String>> byApp = new TreeMap<>();
        for (NamespaceFileMetadata m : all) {
            String rel = stripRoot(m.getPath());
            if (rel == null || rel.isEmpty()) {
                continue;
            }
            String app = firstSegment(rel);
            if (app == null || RESERVED_DESIGNER.equals(app)) {
                continue;
            }
            byApp.computeIfAbsent(app, k -> new ArrayList<>()).add(rel);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byApp.entrySet()) {
            String appName = e.getKey();
            Map<String, Object> appNode = new LinkedHashMap<>();
            appNode.put("appName", appName);
            String flowNs = flowNamespaces.get(appName);
            appNode.put("namespace", flowNs);
            if (flowNs != null && !flowNs.equals(appsFiles.getRootNamespace())) {
                appNode.put("warning", "flow namespace '" + flowNs + "' != apps.files.root-namespace '"
                    + appsFiles.getRootNamespace() + "' — 渲染读的与编辑器写的不是同一个文件，请核对配置");
            }
            appNode.put("pages", buildPagesFromRelative(e.getValue()));
            result.add(appNode);
        }
        return result;
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
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "path query parameter is required");
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.startsWith(CONVENTION_ROOT) || (!normalized.equals(CONVENTION_ROOT) && !normalized.startsWith(CONVENTION_ROOT + "/"))) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "path must be inside " + CONVENTION_ROOT + "/ (got " + path + ")");
        }
        Path p;
        try {
            p = Path.of(normalized).normalize();
        } catch (InvalidPathException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "invalid path: " + path);
        }
        if (!p.startsWith(CONVENTION_ROOT) || p.getNameCount() < 3) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "path must be " + CONVENTION_ROOT + "/{appName}/{page}.json (got " + path + ")");
        }
        // getName(0) = "apps"（约定根）；appName 是第 1 段。
        String appName = p.getName(1).toString();
        if (!SEGMENT.matcher(appName).matches() || RESERVED_DESIGNER.equals(appName)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "invalid or reserved app name in path: " + appName);
        }
        for (int i = 2; i < p.getNameCount(); i++) {
            String seg = p.getName(i).toString();
            if (i == p.getNameCount() - 1) {
                if (!JSON_FILE_NAME.matcher(seg).matches()) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                        "page file must be a *.json file (got " + seg + ")");
                }
            } else if (!SEGMENT.matcher(seg).matches()) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "invalid path segment: " + seg);
            }
        }
        return p;
    }

    /** body 必须是合法 JSON 对象，否则 400。 */
    private void validateJsonObject(String body, String path) {
        if (body == null || body.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "PUT body must be a JSON object (empty body for " + path + ")");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "PUT body must be a JSON object (got non-object for " + path + ")");
            }
        } catch (JsonProcessingException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "PUT body must be valid JSON (" + path + "): " + e.getOriginalMessage());
        }
    }

    /**
     * 从约定根相对路径列表构建页面树（纯函数，便于单测）。
     * 输入形如 {@code ["hello/index.json", "hello/x/", "hello/x/y.json"]}。
     * 节点：{name, kind: "page"|"group", index?: true, children: [...]}；
     * 同名文件 + 目录合并为同一节点（文件 = 自身页，目录 = children）；index.json 置顶。
     */
    static List<Map<String, Object>> buildPagesFromRelative(List<String> relativePaths) {
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        for (String rel : relativePaths) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            String[] parts = rel.split("/");
            if (parts.length < 2) {
                continue; // 一级目录（app 本身）
            }
            if (parts.length == 2) {
                String name = parts[1];
                boolean dir = name.endsWith("/");
                String clean = dir ? name.substring(0, name.length() - 1) : name;
                if (clean.isEmpty()) {
                    continue;
                }
                if (dir) {
                    Map<String, Object> node = nodes.computeIfAbsent(clean, AppsFileController::groupNode);
                    node.putIfAbsent("children", new ArrayList<>());
                } else if (clean.endsWith(".json")) {
                    String base = clean.substring(0, clean.length() - ".json".length());
                    if (base.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> node = nodes.computeIfAbsent(base, AppsFileController::pageNode);
                    node.put("kind", "page");
                    if ("index".equals(base)) {
                        node.put("index", true);
                    }
                }
            } else if (parts.length == 3) {
                String group = parts[1].endsWith("/") ? parts[1].substring(0, parts[1].length() - 1) : parts[1];
                String file = parts[2];
                if (!file.endsWith(".json")) {
                    continue;
                }
                String base = file.substring(0, file.length() - ".json".length());
                if (base.isEmpty() || group.isEmpty()) {
                    continue;
                }
                Map<String, Object> node = nodes.computeIfAbsent(group, AppsFileController::groupNode);
                node.putIfAbsent("children", new ArrayList<>());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                Map<String, Object> child = pageNode(base);
                children.add(child);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(nodes.values());
        for (Map<String, Object> node : out) {
            Object children = node.get("children");
            if (children instanceof List<?> list && !list.isEmpty()) {
                list.sort(Comparator.comparing((Object o) -> (String) ((Map<?, ?>) o).get("name")));
            }
        }
        out.sort(Comparator
            .comparing((Map<String, Object> n) -> Boolean.TRUE.equals(n.get("index")) ? 0 : 1)
            .thenComparing(n -> (String) n.get("name")));
        return out;
    }

    private static Map<String, Object> pageNode(String name) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("kind", "page");
        node.put("children", new ArrayList<>());
        return node;
    }

    private static Map<String, Object> groupNode(String name) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", name);
        node.put("kind", "group");
        node.put("children", new ArrayList<>());
        return node;
    }

    private Namespace namespace(String tenant) {
        return namespaceFactory.of(tenant, appsFiles.getRootNamespace(), storageInterface);
    }

    /** "/apps/hello/index.json" → "hello/index.json"；"/apps/hello/" → "hello/"。 */
    private static String stripRoot(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.equals(CONVENTION_ROOT) || p.equals(CONVENTION_ROOT + "/")) {
            return "";
        }
        if (!p.startsWith(CONVENTION_ROOT + "/")) {
            return null;
        }
        return p.substring(CONVENTION_ROOT.length() + 1);
    }

    private static String firstSegment(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(0, slash);
    }
}
