package io.kestra.webserver.controllers.api;

import java.util.List;
import java.util.Optional;

import io.kestra.core.exceptions.ConflictException;
import io.kestra.core.exceptions.InvalidException;
import io.kestra.core.mcp.models.McpServer;
import io.kestra.core.mcp.repositories.McpServerRepositoryInterface;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.EditionProvider;
import io.kestra.core.utils.ListUtils;
import io.kestra.plugin.core.trigger.McpToolTrigger;
import io.kestra.webserver.models.api.ApiMcpServer;
import io.kestra.webserver.responses.PagedResults;
import io.kestra.webserver.utils.PageableUtils;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;

@Controller("/api/v1/{tenant}/mcp-servers")
@Slf4j
public class McpServerController {

    private final McpServerRepositoryInterface mcpServerRepository;
    private final TenantService tenantService;
    private final EditionProvider editionProvider;
    private final FlowRepositoryInterface flowRepository;

    @Inject
    public McpServerController(
        McpServerRepositoryInterface mcpServerRepository,
        TenantService tenantService,
        EditionProvider editionProvider,
        FlowRepositoryInterface flowRepository) {
        this.mcpServerRepository = mcpServerRepository;
        this.tenantService = tenantService;
        this.editionProvider = editionProvider;
        this.flowRepository = flowRepository;
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get
    @Operation(tags = { "Mcp" }, summary = "List MCP servers")
    public PagedResults<ApiMcpServer> listMcps(
        @Parameter(description = "The current page") @QueryValue(defaultValue = "1") @Min(1) int page,
        @Parameter(description = "The current page size") @QueryValue(defaultValue = "10") @Min(1) @Max(PageableUtils.MAX_PAGE_SIZE) int size,
        @Parameter(description = "The sort of current page") @Nullable @QueryValue List<String> sort) {
        return PagedResults.of(
            mcpServerRepository.find(PageableUtils.from(page, size, sort), tenantService.resolveTenant())
                .map(ApiMcpServer::from)
        );
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "{id}")
    @Operation(tags = { "Mcp" }, summary = "Get an MCP server")
    public ApiMcpServer getMcp(
        @Parameter(description = "The MCP server id") @PathVariable String id) {
        return mcpServerRepository.get(tenantService.resolveTenant(), id)
            .map(ApiMcpServer::from)
            .orElse(null);
    }

    @ExecuteOn(TaskExecutors.IO)
    @Post
    @Operation(tags = { "Mcp" }, summary = "Create an MCP server")
    public HttpResponse<ApiMcpServer> createMcp(
        @RequestBody(description = "The MCP server to create") @Body @Valid ApiMcpServer mcpServer) {
        String tenantId = tenantService.resolveTenant();

        if (McpServer.DEFAULT_ID.equals(mcpServer.id())) {
            throw new InvalidException(mcpServer, "MCP id '" + McpServer.DEFAULT_ID + "' is reserved");
        }

        if (mcpServerRepository.get(tenantId, mcpServer.id()).isPresent()) {
            throw new ConflictException("MCP server already exists with id: '" + mcpServer.id() + "'");
        }

        ApiMcpServer validated = validateMcp(mcpServer);

        McpServer toSave = new McpServer(
            tenantId,
            validated.id(), validated.description(), validated.instructions(),
            validated.serverType(), validated.authType(), validated.oauthProvider(),
            validated.oauthScopesSupported(),
            validated.disabled(), false, false, null, null
        );

        return HttpResponse.ok(ApiMcpServer.from(mcpServerRepository.save(null, toSave)));
    }

    @ExecuteOn(TaskExecutors.IO)
    @Put(uri = "{id}")
    @Operation(tags = { "Mcp" }, summary = "Update an MCP server")
    public HttpResponse<ApiMcpServer> updateMcp(
        @Parameter(description = "The MCP server id") @PathVariable String id,
        @RequestBody(description = "The MCP server to update") @Body @Valid ApiMcpServer mcpServer) {
        String tenantId = tenantService.resolveTenant();

        Optional<McpServer> existing = mcpServerRepository.get(tenantId, id);
        if (existing.isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "MCP server not found: " + id);
        }

        if (McpServer.DEFAULT_ID.equals(mcpServer.id()) != existing.get().isDefault()) {
            throw new InvalidException(mcpServer, "MCP id '" + McpServer.DEFAULT_ID + "' is reserved");
        }

        // dsh：合并语义 —— 请求体缺省（null）的字段保留原值，避免部分字段 PUT 清空其余字段。
        // 显式清空字符串字段请传 ""，清空 scope 列表请传 []。
        ApiMcpServer merged = new ApiMcpServer(
            id,
            mcpServer.description() != null ? mcpServer.description() : existing.get().description(),
            mcpServer.instructions() != null ? mcpServer.instructions() : existing.get().instructions(),
            mcpServer.serverType() != null ? mcpServer.serverType() : existing.get().serverType(),
            mcpServer.authType() != null ? mcpServer.authType() : existing.get().authType(),
            mcpServer.oauthProvider() != null ? mcpServer.oauthProvider() : existing.get().oauthProvider(),
            mcpServer.oauthScopesSupported() != null ? mcpServer.oauthScopesSupported() : existing.get().oauthScopesSupported(),
            mcpServer.disabled() != null ? mcpServer.disabled() : existing.get().disabled(),
            existing.get().isDefault(), existing.get().created(), existing.get().updated()
        );

        ApiMcpServer validated = validateMcp(merged);

        McpServer toSave = new McpServer(
            tenantId, id,
            validated.description(), validated.instructions(),
            validated.serverType(), validated.authType(), validated.oauthProvider(),
            validated.oauthScopesSupported(),
            validated.disabled(), false, false, null, null
        );

        return HttpResponse.ok(ApiMcpServer.from(mcpServerRepository.save(existing.get(), toSave)));
    }

    /**
     * dsh 默认值：内置 kestra-oidc 是唯一 OAuth 提供商；缺省时由服务器落库，
     * UI 只负责展示（docs/mcp-oauth.md）。
     */
    private static final String DEFAULT_OAUTH_PROVIDER = "kestra-oidc";
    private static final java.util.List<String> DEFAULT_OAUTH_SCOPES =
        java.util.List.of("openid", "profile", "email", "mcp");

    /** Rebuilds the DTO with OAuth default fields filled in (OAUTH authType only). */
    private static ApiMcpServer withOAuthDefaults(ApiMcpServer s, String provider, java.util.List<String> scopes) {
        return new ApiMcpServer(
            s.id(), s.description(), s.instructions(), s.serverType(), s.authType(),
            provider, scopes, s.disabled(), s.isDefault(), s.created(), s.updated()
        );
    }

    /** Rebuilds the DTO with a forced serverType (dsh：PUBLIC 已移除，统一 PRIVATE). */
    private static ApiMcpServer withServerType(ApiMcpServer s, McpServer.ServerType serverType) {
        return new ApiMcpServer(
            s.id(), s.description(), s.instructions(), serverType, s.authType(),
            s.oauthProvider(), s.oauthScopesSupported(), s.disabled(), s.isDefault(), s.created(), s.updated()
        );
    }

    protected ApiMcpServer validateMcp(ApiMcpServer mcpServer) {
        // dsh：缺省字段归一为项目默认（PRIVATE + OAUTH + 启用）；PUBLIC 已移除，一律转 PRIVATE。
        ApiMcpServer normalized = new ApiMcpServer(
            mcpServer.id(),
            mcpServer.description(),
            mcpServer.instructions(),
            mcpServer.serverType() == null ? McpServer.ServerType.PRIVATE : mcpServer.serverType(),
            mcpServer.authType() == null ? McpServer.AuthType.OAUTH : mcpServer.authType(),
            mcpServer.oauthProvider(),
            mcpServer.oauthScopesSupported(),
            mcpServer.disabled() == null ? false : mcpServer.disabled(),
            mcpServer.isDefault(), mcpServer.created(), mcpServer.updated()
        );
        if (normalized.serverType() == McpServer.ServerType.PUBLIC) {
            normalized = withServerType(normalized, McpServer.ServerType.PRIVATE);
        }

        McpServer.AuthType authType = normalized.authType();

        if (
            editionProvider.get() == EditionProvider.Edition.OSS
                && authType == McpServer.AuthType.API_TOKEN
        ) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Auth type 'API_TOKEN' requires Enterprise Edition");
        }
        // OAUTH 在本 fork 的 OSS 上可用：MCP 客户端以内置 kestra-oidc 为授权服务器
        // （RFC 8414 发现 + RFC 7591 动态注册，OidcMcpOAuthController / OidcMcpBearerAuthFilter，
        // docs/mcp-oauth.md），与上游 EE 的"接入第三方 OAuth Provider"语义一致但实现不同。

        boolean hasOauthProvider = normalized.oauthProvider() != null && !normalized.oauthProvider().isBlank();
        boolean hasScopes = !ListUtils.isEmpty(normalized.oauthScopesSupported());

        if (authType == McpServer.AuthType.OAUTH) {
            // dsh：缺省字段由服务器落默认值（唯一提供商 + 内置 scope 集），不再要求客户端必填
            return withOAuthDefaults(
                normalized,
                hasOauthProvider ? normalized.oauthProvider() : DEFAULT_OAUTH_PROVIDER,
                hasScopes ? normalized.oauthScopesSupported() : DEFAULT_OAUTH_SCOPES
            );
        }

        if (hasOauthProvider) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "oauthProvider must not be set when authType is not OAUTH");
        }
        if (hasScopes) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "oauthScopesSupported must not be set when authType is not OAUTH");
        }
        return normalized;
    }

    @ExecuteOn(TaskExecutors.IO)
    @Delete(uri = "{id}")
    @Operation(tags = { "Mcp" }, summary = "Delete an MCP server")
    public HttpResponse<Void> deleteMcp(
        @Parameter(description = "The MCP server id") @PathVariable String id) {
        String tenantId = tenantService.resolveTenant();
        Optional<McpServer> existing = mcpServerRepository.get(tenantId, id);
        if (existing.isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "MCP server not found: " + id);
        }
        if (existing.get().isDefault()) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "The default MCP server cannot be deleted");
        }
        return mcpServerRepository.delete(tenantId, id)
            .map(ignored -> HttpResponse.<Void> status(HttpStatus.NO_CONTENT))
            .orElse(HttpResponse.status(HttpStatus.NOT_FOUND));
    }

    @ExecuteOn(TaskExecutors.IO)
    @Get(uri = "{id}/tools")
    @Operation(tags = { "Mcp" }, summary = "List tools exposed by an MCP server")
    public List<ApiMcpTool> listTools(
        @Parameter(description = "The MCP server id") @PathVariable String id) {
        String tenantId = tenantService.resolveTenant();
        if (mcpServerRepository.get(tenantId, id).isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "MCP server not found: " + id);
        }
        return flowRepository.find(Pageable.unpaged(), tenantId, McpToolTrigger.class).stream()
            .flatMap(
                flow -> flow.getTriggers().stream()
                    .filter(t -> t instanceof McpToolTrigger)
                    .map(t -> (McpToolTrigger) t)
                    .filter(t -> id.equals(t.getMcpServer() == null ? McpToolTrigger.DEFAULT_SERVER_ID : t.getMcpServer()))
                    .map(t -> ApiMcpTool.from(flow, t))
            )
            .toList();
    }

    @ExecuteOn(TaskExecutors.IO)
    @Patch(uri = "{id}/toggle")
    @Operation(tags = { "Mcp" }, summary = "Toggle an MCP server's enabled state")
    public HttpResponse<ApiMcpServer> toggleMcp(
        @Parameter(description = "The MCP server id") @PathVariable String id) {
        String tenantId = tenantService.resolveTenant();
        Optional<McpServer> existing = mcpServerRepository.get(tenantId, id);
        if (existing.isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "MCP server not found: " + id);
        }
        McpServer mcpServer = existing.get();
        McpServer toggled = new McpServer(
            tenantId, mcpServer.id(),
            mcpServer.description(), mcpServer.instructions(),
            mcpServer.serverType(), mcpServer.authType(), mcpServer.oauthProvider(),
            mcpServer.oauthScopesSupported(),
            !mcpServer.disabled(), false, false, null, null
        );
        return HttpResponse.ok(ApiMcpServer.from(mcpServerRepository.save(mcpServer, toggled)));
    }

    /**
     * API DTO for a single tool exposed by an MCP server. A tool is the projection of an
     * {@link McpToolTrigger} on a flow: one trigger emits one tool. Carries the MCP-facing
     * identifiers/metadata plus the originating flow location so the admin UI can cross-link
     * to the flow that defines it.
     */
    public record ApiMcpTool(
        @Schema(description = "Unique MCP tool identifier (the trigger's `toolName`). This is the name AI agents use to invoke the tool.") String toolName,

        @Schema(description = "Trigger id within the flow (the `id` field of the McpToolTrigger). Distinct from `toolName`.") String triggerId,

        @Schema(description = "Human-readable display title shown to AI agents.") String title,

        @Schema(description = "Description of what the tool does and when an AI agent should call it.") String description,

        @Schema(description = "MCP tool behavioural annotations.") Annotations annotations,

        @Schema(description = "Namespace of the flow that defines this tool.") String namespace,

        @Schema(description = "Id of the flow that defines this tool.") String flowId,

        @Schema(description = "Revision of the flow that defines this tool.") Integer flowRevision,

        @Schema(description = "Whether this tool is currently disabled (trigger disabled or flow disabled).") boolean disabled) {

        public record Annotations(
            boolean readOnly,
            boolean openWorld,
            boolean destructive,
            boolean idempotent,
            boolean returnDirect) {
            public static Annotations from(McpToolTrigger.Annotations a) {
                if (a == null) {
                    return new Annotations(false, false, false, false, false);
                }
                return new Annotations(a.readOnly(), a.openWorld(), a.destructive(), a.idempotent(), a.returnDirect());
            }
        }

        public static ApiMcpTool from(Flow flow, McpToolTrigger trigger) {
            return new ApiMcpTool(
                trigger.getToolName(),
                trigger.getId(),
                trigger.getTitle(),
                trigger.getToolDescription(),
                Annotations.from(trigger.getAnnotations()),
                flow.getNamespace(),
                flow.getId(),
                flow.getRevision(),
                flow.isDisabled() || trigger.isDisabled()
            );
        }
    }
}
