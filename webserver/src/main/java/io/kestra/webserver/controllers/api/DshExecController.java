package io.kestra.webserver.controllers.api;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.Label;
import io.kestra.core.models.executions.ExecutionKilled;
import io.kestra.core.models.executions.ExecutionKilledExecution;
import io.kestra.core.queues.BroadcastQueueInterface;
import io.kestra.core.events.CrudEvent;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.services.FlowService;
import io.kestra.core.services.WebhookService;
import io.kestra.core.storages.Namespace;
import io.kestra.core.storages.NamespaceFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * dsh 云电脑模式触发端点（docs/dsh-云电脑模式需求规格.md §2.7/§2.8/§3-A/§3-C）：
 * 手机端（aud=dsh-ui）触发全局执行 flow、上传任务素材、轮询执行结果的封装面——
 * 原生 {@code ExecutionController} 管理面不暴露给手机端普通用户。
 *
 * <p>
 * 授权模型（规格 §2.7）：调用者身份取自 OidcBearerAuthFilter 已校验的 claims；
 * 员工 namespace 一律由服务端从 token sub 重新推导（请求体 namespace/userId 忽略，
 * 伪造不可达）；flow 白名单为服务端常量单条 {@code (dsh.exec, exec-run)}，不读请求体
 * flowId。执行打 labels {@code dsh.sessionId} / {@code dsh.sub}，轮询按 label 校验归属
 * （非本人执行 404，防 execution-id oracle）。
 *
 * <p>
 * 契约形态复用 apps 已验证模式（{@code AppRouterController.api()}）：进程内创建执行 →
 * {@code 202 + {executionId, executionState, executionUrl}}；{@code executionUrl} 是本控制器的
 * 自包装轮询端点（相对路径，手机端拼 API_BASE），不是原生管理面 URL。终态回复从员工
 * namespace 的 {@code sessions/{sid}/reply.txt} 读取（执行 flow 的 persist 任务回写）。
 */
@Controller("/api/v1/dsh/exec")
@ExecuteOn(TaskExecutors.IO)
@Slf4j
public class DshExecController {

    /** 全局执行 flow 白名单（规格 §4.1）：唯一可触发项，不读请求体。 */
    static final String EXEC_FLOW_NAMESPACE = "dsh.exec";
    static final String EXEC_FLOW_ID = "exec-run";

    static final String LABEL_SESSION = "dsh.sessionId";
    static final String LABEL_SUB = "dsh.sub";

    /** 上传文件默认上限 20MB（compose 可配 EXEC_UPLOAD_MAX_BYTES，规格 §4.4）。 */
    static final long DEFAULT_UPLOAD_MAX_BYTES = 20L * 1024 * 1024;

    /** 任务文本服务端兜底上限（手机端输入框限 2000；防绕过 UI 直打 LLM 的滥用）。 */
    static final int MAX_TASK_TEXT_LENGTH = 10_000;

    /** 手机端公开客户端（aud 要求，规格 §2.7）。 */
    private static final String AUD_DSH_UI = "dsh-ui";

    /** 并发守卫关心的非终态（同会话有活跃执行 → 409，规格 §4.1 labels 并发查询）。 */
    private static final Set<State.Type> ACTIVE_STATES = Set.of(
        State.Type.CREATED, State.Type.RUNNING, State.Type.PAUSED, State.Type.QUEUED,
        State.Type.RETRYING, State.Type.KILLING, State.Type.RESTARTED);

    @Inject
    private FlowService flowService;

    @Inject
    private WebhookService webhookService;

    @Inject
    private FlowInputOutput flowInputOutput;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Inject
    private TenantService tenantService;

    @Inject
    private NamespaceFactory namespaceFactory;

    @Inject
    private BroadcastQueueInterface<ExecutionKilled> killQueue;

    @Inject
    private io.micronaut.context.event.ApplicationEventPublisher<CrudEvent<Execution>> eventPublisher;

    @Inject
    private StorageInterface storageInterface;

    /** 触发请求体（employeeNamespace/userId 一律服务端推导注入，请求体同名字段忽略）。 */
    public record RunRequest(String sessionId, String text, List<String> fileRefs) {}

    /** 手机端触发一次云电脑任务（新会话或追问，由 sessions/{sid} 是否已有会话目录决定）。 */
    @Post(uri = "/run")
    @Operation(summary = "Trigger one dsh cloud-computer execution for the caller's session")
    public HttpResponse<Map<String, Object>> run(HttpRequest<?> request, @Body RunRequest body) {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) {
            return unauthorized();
        }
        HttpResponse<Map<String, Object>> rejected = rejectNonMobile(caller);
        if (rejected != null) {
            return rejected;
        }
        if (body == null || isBlank(body.sessionId()) || isBlank(body.text())) {
            return HttpResponse.badRequest(Map.of("error", "sessionId and text are required"));
        }
        if (body.text().length() > MAX_TASK_TEXT_LENGTH) {
            return HttpResponse.badRequest(Map.of("error", "text too long",
                "limitChars", MAX_TASK_TEXT_LENGTH, "actualChars", body.text().length()));
        }
        String sessionId = body.sessionId().trim();
        if (!sessionId.matches("[0-9a-fA-F-]{8,64}")) {
            return HttpResponse.badRequest(Map.of("error", "sessionId must be an id-shaped token"));
        }
        String employeeNamespace = DshEmployeeNamespace.of(caller.sub());

        List<String> fileRefs = new ArrayList<>();
        if (body.fileRefs() != null) {
            for (String ref : body.fileRefs()) {
                if (ref == null) {
                    continue;
                }
                // 上传引用只能指向本人会话的 uploads/ 目录（相对路径或上传端点返回的
                // kestra:// URI 均可；namespace 由服务端推导，URI 中的 namespace 必须与推导一致）
                String normalized = normalizeFileRef(ref, sessionId, employeeNamespace);
                if (normalized == null) {
                    return HttpResponse.badRequest(Map.of("error", "invalid fileRef", "fileRef", ref));
                }
                fileRefs.add(normalized);
            }
        }

        // 并发守卫：同 sessionId 有活跃执行 → 409（手机端应等待上一任务终态或重试）。
        // 查询超时（DB 极慢）→ 503 而非放行：双执行会并发写同一会话 home（文件即记忆），
        // 漏检的代价高于误拒；503 语义 = 稍后重试。blockFirst 超时抛 IllegalStateException，
        // 必须捕获（否则 500）。
        Boolean busy;
        try {
            busy = executionRepository.find(null, tenantService.resolveTenant(), null, EXEC_FLOW_NAMESPACE, EXEC_FLOW_ID,
                null, null, List.copyOf(ACTIVE_STATES), Map.of(LABEL_SESSION, sessionId), null)
                .blockFirst(Duration.ofSeconds(5)) != null;
        } catch (Exception e) {
            log.warn("dsh exec concurrency check timed out for session {}", sessionId, e);
            return HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "concurrency check unavailable, retry shortly"));
        }
        if (busy) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT)
                .body(Map.of("error", "session_busy", "sessionId", sessionId));
        }

        final Flow flow;
        try {
            flow = flowService.getFlowIfExecutableOrThrow(tenantService.resolveTenant(), EXEC_FLOW_NAMESPACE, EXEC_FLOW_ID, Optional.empty());
        } catch (NoSuchElementException e) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "exec flow not available", "flow", EXEC_FLOW_NAMESPACE + "/" + EXEC_FLOW_ID));
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("userId", caller.sub());
        inputs.put("employeeNamespace", employeeNamespace);
        inputs.put("sessionId", sessionId);
        inputs.put("text", body.text());
        // Pebble 注入防护（2026-09-28 轮次4）：kestra 对 env Property 的渲染会对值做
        // 二次求值（实测 text 含 {{ 7*7 }} 传到 agent 已变 49）——用户自由文本直入 env
        // 构成表达式注入面（{{ secret(...) }} 会把中台秘密泄漏进 LLM 上下文）。
        // textB64 走 base64 通道（字母表无 {{ }}，无法被求值），flow 侧 sh 内解码使用。
        inputs.put("textB64", java.util.Base64.getEncoder()
            .encodeToString(body.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        inputs.put("fileRefs", fileRefs);

        Execution execution = Execution.builder()
            .id(io.kestra.core.utils.IdUtils.create())
            .tenantId(flow.getTenantId())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .flowRevision(flow.getRevision())
            .inputs(new LinkedHashMap<>())
            .state(new State())
            .labels(List.of(
                new Label(LABEL_SESSION, sessionId),
                new Label(LABEL_SUB, caller.sub())))
            .build();
        try {
            Map<String, Object> rendered = flowInputOutput.readExecutionInputs(flow, execution, inputs);
            execution = execution.withInputs(rendered);
        } catch (Exception e) {
            return HttpResponse.badRequest(Map.of("error", "invalid inputs", "detail", String.valueOf(e.getMessage())));
        }

        try {
            webhookService.startExecution(execution).block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Unable to start dsh exec execution for session {}", sessionId, e);
            return HttpResponse.serverError(Map.of("error", "unable to start execution", "detail", String.valueOf(e.getMessage())));
        }

        String executionUrl = "/api/v1/dsh/exec/run/" + execution.getId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.getId());
        result.put("executionState", execution.getState().getCurrent());
        result.put("executionUrl", executionUrl);
        return HttpResponse.status(io.micronaut.http.HttpStatus.ACCEPTED)
            .header("Location", executionUrl)
            .body(result);
    }

    /** 手机端轮询执行状态；终态附回复（reply）或失败摘要。 */
    @Get(uri = "/run/{executionId}")
    @Operation(summary = "Poll one dsh cloud-computer execution (ownership enforced via dsh.sub label)")
    public HttpResponse<Map<String, Object>> poll(HttpRequest<?> request, String executionId) {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) {
            return unauthorized();
        }
        HttpResponse<Map<String, Object>> rejected = rejectNonMobile(caller);
        if (rejected != null) {
            return rejected;
        }
        Optional<Execution> maybe = executionRepository.findByIdWithoutAcl(tenantService.resolveTenant(), executionId);
        if (maybe.isEmpty()) {
            return notFound();
        }
        Execution execution = maybe.get();
        // 归属校验（防 execution-id oracle）：非本人执行与不存在同形（404）
        String owner = labelValue(execution, LABEL_SUB);
        if (!caller.sub().equals(owner)) {
            return notFound();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.getId());
        result.put("executionState", execution.getState().getCurrent());
        State.Type state = execution.getState().getCurrent();
        if (!state.isTerminated()) {
            return HttpResponse.ok(result);
        }
        if (state.isFailed() || state == State.Type.KILLED) {
            result.put("error", "execution ended with state " + state);
            result.put("sessionId", execution.getInputs().get("sessionId"));
            return HttpResponse.ok(result);
        }
        // SUCCESS / WARNING：从员工 namespace 读 persist 回写的 reply.txt
        String reply = readReply(execution);
        if (reply == null) {
            result.put("error", "reply not found for finished execution");
        } else {
            result.put("reply", reply);
        }
        result.put("sessionId", execution.getInputs().get("sessionId"));
        return HttpResponse.ok(result);
    }

    /** 手机端取消自己的执行（发错任务不必干等超时；KILL 后手机端可重试）。 */
    @io.micronaut.http.annotation.Delete(uri = "/run/{executionId}")
    @Operation(summary = "Cancel one dsh cloud-computer execution (ownership enforced via dsh.sub label)")
    public HttpResponse<Map<String, Object>> cancel(HttpRequest<?> request, String executionId) {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) {
            return unauthorized();
        }
        HttpResponse<Map<String, Object>> rejected = rejectNonMobile(caller);
        if (rejected != null) {
            return rejected;
        }
        Optional<Execution> maybe = executionRepository.findByIdWithoutAcl(tenantService.resolveTenant(), executionId);
        if (maybe.isEmpty()) {
            return notFound();
        }
        Execution execution = maybe.get();
        if (!caller.sub().equals(labelValue(execution, LABEL_SUB))) {
            return notFound();
        }
        if (execution.getState().isTerminated()) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT)
                .body(Map.of("error", "already terminated", "executionState", execution.getState().getCurrent()));
        }
        eventPublisher.publishEvent(CrudEvent.of(execution, execution.withState(State.Type.KILLING)));
        try {
            killQueue.emit(ExecutionKilledExecution.builder()
                .state(ExecutionKilled.State.REQUESTED)
                .executionId(execution.getId())
                .isOnKillCascade(true)
                .tenantId(tenantService.resolveTenant())
                .build());
        } catch (Exception e) {
            log.error("dsh exec cancel emit failed for {}", executionId, e);
            return HttpResponse.serverError(Map.of("error", "cancel emit failed", "detail", String.valueOf(e.getMessage())));
        }
        log.info("dsh exec cancel: sub={} execution={} session={}", caller.sub(), executionId,
            execution.getInputs().get("sessionId"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.getId());
        result.put("executionState", State.Type.KILLING);
        return HttpResponse.ok(result);
    }

    /** 手机端上传任务素材到本人员工命名空间（规格 §4.4：≤20MB、类型不限、同名覆盖）。 */
    @Post(uri = "/files", consumes = MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload one task asset into the caller's employee namespace session uploads")
    public HttpResponse<Map<String, Object>> upload(
        HttpRequest<?> request,
        @Part("sessionId") String sessionId,
        @Part("file") CompletedFileUpload file
    ) throws java.io.IOException {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) {
            return unauthorized();
        }
        HttpResponse<Map<String, Object>> rejected = rejectNonMobile(caller);
        if (rejected != null) {
            return rejected;
        }
        if (isBlank(sessionId) || !sessionId.trim().matches("[0-9a-fA-F-]{8,64}")) {
            return HttpResponse.badRequest(Map.of("error", "sessionId is required"));
        }
        if (file == null || isBlank(file.getFilename())) {
            return HttpResponse.badRequest(Map.of("error", "file is required"));
        }
        String filename = sanitizeFilename(file.getFilename());
        if (filename == null) {
            return HttpResponse.badRequest(Map.of("error", "invalid filename"));
        }
        long maxBytes = uploadMaxBytes();
        if (file.getSize() > maxBytes) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                .body(Map.of("error", "file too large", "limitBytes", maxBytes, "sizeBytes", file.getSize()));
        }

        String employeeNamespace = DshEmployeeNamespace.of(caller.sub());
        Path destination = Path.of("/sessions/" + sessionId.trim() + "/uploads/" + filename);
        Namespace namespaceStorage = namespaceFactory.of(tenantService.resolveTenant(), employeeNamespace, storageInterface);
        boolean overwritten;
        try {
            overwritten = namespaceStorage.exists(destination);
            try (InputStream in = file.getInputStream()) {
                namespaceStorage.putFile(destination, in);
            }
        } catch (java.net.URISyntaxException e) {
            return HttpResponse.serverError(Map.of("error", "storage write failed", "detail", String.valueOf(e.getMessage())));
        }
        log.info("dsh exec upload: sub={} sessionId={} file={} bytes={} overwritten={}",
            caller.sub(), sessionId, filename, file.getSize(), overwritten);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", "kestra://" + employeeNamespace + destination);
        result.put("size", file.getSize());
        result.put("overwritten", overwritten);
        return HttpResponse.ok(result);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** 403 when the authenticated caller is not a human on the mobile public client (aud=dsh-ui); null to proceed. */
    private static HttpResponse<Map<String, Object>> rejectNonMobile(DshIdentity.Principal caller) {
        if (caller.isService() || !AUD_DSH_UI.equals(caller.clientId())) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of("error", "mobile user token (aud=dsh-ui) required"));
        }
        return null;
    }

    private static HttpResponse<Map<String, Object>> unauthorized() {
        return HttpResponse.status(io.micronaut.http.HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "authentication required"));
    }

    private static HttpResponse<Map<String, Object>> notFound() {
        return HttpResponse.notFound(Map.of("error", "execution not found"));
    }

    private static String labelValue(Execution execution, String key) {
        if (execution.getLabels() == null) {
            return null;
        }
        return execution.getLabels().stream().filter(l -> key.equals(l.key())).map(Label::value).findFirst().orElse(null);
    }

    /** Reads the reply.txt persisted by the flow's persist task; null when absent/unreadable. */
    private String readReply(Execution execution) {
        Object sid = execution.getInputs().get("sessionId");
        String sub = labelValue(execution, LABEL_SUB);
        if (!(sid instanceof String sessionId) || sub == null) {
            return null;
        }
        try {
            Namespace namespaceStorage = namespaceFactory.of(execution.getTenantId(), DshEmployeeNamespace.of(sub), storageInterface);
            Path path = Path.of("/sessions/" + sessionId + "/reply.txt");
            if (!namespaceStorage.exists(path)) {
                return null;
            }
            try (InputStream in = namespaceStorage.getFileContent(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("dsh exec reply read failed for execution {}", execution.getId(), e);
            return null;
        }
    }

    private static long uploadMaxBytes() {
        String raw = System.getenv("EXEC_UPLOAD_MAX_BYTES");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_UPLOAD_MAX_BYTES;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_UPLOAD_MAX_BYTES;
        } catch (NumberFormatException e) {
            return DEFAULT_UPLOAD_MAX_BYTES;
        }
    }

/** Accepts `sessions/{sid}/uploads/name` or `kestra://{employeeNs}/sessions/{sid}/uploads/name`; returns the relative form, null when foreign. */
    private static String normalizeFileRef(String ref, String sessionId, String employeeNamespace) {
        String value = ref;
        String prefix = "kestra://" + employeeNamespace + "/";
        if (value.startsWith(prefix)) {
            value = value.substring(prefix.length());
        } else if (value.startsWith("kestra://")) {
            return null; // 他人 namespace 的 URI（namespace 由服务端推导，不接受任何其他值）
        }
        if (!value.startsWith("sessions/" + sessionId + "/uploads/") || value.contains("..")) {
            return null;
        }
        return value;
    }

    /** Strips any path components; null when nothing safe remains. */

    private static String sanitizeFilename(String raw) {
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255) {
            return null;
        }
        // 全集控制字符防御（轮次12）：ISO C0/C1 控制字符一律拒绝——multipart 的
        // quoted-string 语法允许携带，浏览器正常不会发，但不能信任调用方。
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return null;
            }
        }
        return name;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
