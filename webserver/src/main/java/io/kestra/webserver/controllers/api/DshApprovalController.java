package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;

import io.kestra.core.executor.command.ExecutionCommand;
import io.kestra.core.executor.command.Resume;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.queues.DispatchQueueInterface;
import io.kestra.core.services.ExecutionService;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.core.flow.Pause;import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * dsh-ui (mobile) approval API (dsh.docx 安全审批): lets a human approver list,
 * inspect and decide dsh_approval tickets. Deciding an APPROVED ticket resumes
 * its owning session (pending_approval → running) — the wake-up channel from
 * dsh-ui back into the orchestration. Tickets with a configured approvers list
 * only accept decisions from those approvers.
 */
@Controller("/api/v1/dsh/approvals")
@ExecuteOn(TaskExecutors.IO)
public class DshApprovalController {

    private static final java.util.regex.Pattern UUID_PATTERN =
        java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", 2);

    private static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value.toLowerCase()).matches();
    }

    private final DshMetricsConfiguration configuration;

    @jakarta.inject.Inject
    protected DispatchQueueInterface<ExecutionCommand> executionCommandQueue;

    @jakarta.inject.Inject
    protected ExecutionService executionService;

    @jakarta.inject.Inject
    protected TenantService tenantService;

    public DshApprovalController(DshMetricsConfiguration configuration) {
        this.configuration = configuration;
    }

    /** Approval inbox: list tickets by status (dsh-ui 审批收件箱). Visible without admin role:
     *  only tickets addressed to the caller (approvers contains sub) or unaddressed ones. */
    @Get
    @Operation(summary = "List dsh approval tickets by status (default PENDING; approver-scoped)")
    public HttpResponse<?> list(
        HttpRequest<?> request,
        @Parameter(description = "Filter by status (PENDING/APPROVED/REJECTED)") @QueryValue(defaultValue = "PENDING") String status
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return HttpResponse.unauthorized();
        // Admin and service identities (machine client_credentials, e.g. dsh observation
        // centre) see every ticket; human approvers are scoped to tickets they can approve.
        boolean scoped = !(caller.isService() || caller.isAdmin());
        String sql = """
            SELECT id::text, session_id::text, type, payload::text, approvers, status, approver, comment, timeout_seconds, created_at, decided_at
            FROM dsh_approval WHERE status = ?
            """ + (scoped ? "AND (approvers IS NULL OR cardinality(approvers) = 0 OR ? = ANY(approvers)) " : "")
            + "ORDER BY created_at DESC LIMIT 200";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status.toUpperCase());
            if (scoped) ps.setString(2, caller.sub());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(row(rs));
            }
        }
        return HttpResponse.ok(rows);
    }

    /** Payload to create a dsh approval ticket (Worker DshApproval CREATE). */
    public record ApprovalCreate(String sessionId, String type, String payload, List<String> approvers,
                                 Integer timeoutSeconds) {}

    /**
     * Create a PENDING approval ticket (Worker DshApproval CREATE / AIAgent high-risk decisions).
     * The ticket is bound to the caller's session id; the flow-facing audit fields (approvers /
     * timeout) are taken verbatim from the payload. Auth: any authenticated dsh caller
     * (dsh-ui users create via dsh(PC); the Worker plugin creates as the {@code dsh} service
     * identity). A missing session is rejected via the foreign key (400).
     */
    @Post
    @Operation(summary = "Create a dsh approval ticket (Worker DshApproval CREATE)")
    public HttpResponse<?> create(
        HttpRequest<?> request,
        @Body ApprovalCreate body
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "authentication required"));
        if (body == null || body.sessionId() == null || !isUuid(body.sessionId())) {
            return HttpResponse.badRequest().body(Map.of("error", "sessionId must be a valid UUID"));
        }
        UUID id = UUID.randomUUID();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO dsh_approval (id, session_id, type, payload, approvers, status, timeout_seconds) "
                + "VALUES (?::uuid, ?::uuid, ?, ?::jsonb, ?, 'PENDING', ?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, body.sessionId());
            ps.setString(3, body.type() == null || body.type().isBlank() ? "generic" : body.type());
            ps.setString(4, body.payload() == null ? "{}" : body.payload());
            List<String> approvers = body.approvers() == null ? List.of() : body.approvers();
            ps.setArray(5, connection.createArrayOf("text", approvers.toArray()));
            ps.setInt(6, body.timeoutSeconds() == null ? 3600 : body.timeoutSeconds());
            ps.executeUpdate();
        }
        return HttpResponse.ok(readRow(id.toString()));
    }

    /**
     * Decide a ticket (approve/reject) as a human approver via dsh-ui, or programmatically as the
     * Worker plugin (DshApproval DECIDE). The approver identity is the authenticated caller (OIDC
     * sub) unless the caller holds the admin role or a service identity, in which case the
     * {@code approver} query parameter is honored (Flow-driven audit field). An expired PENDING
     * ticket is auto-rejected (matches the pre-refactor DshStore semantics). An approved decision
     * resumes the owning session (pending_approval → running); expired or already-decided tickets
     * are rejected with 409/404 semantics.
     */
    @Post("/{approvalId}/decide")
    @Operation(summary = "Approve or reject a ticket; resumes the owning session on approval")
    public HttpResponse<?> decide(
        HttpRequest<?> request,
        @Parameter(description = "Ticket id") String approvalId,
        @Parameter(description = "true = approve, false = reject") @QueryValue(defaultValue = "true") boolean approved,
        @Parameter(description = "Decision comment") @QueryValue(defaultValue = "") String comment,
        @Parameter(description = "Approver identity recorded on the ticket (service/admin tokens may set it)") @QueryValue(defaultValue = "") String approver
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "authentication required"));
        try {
            return doDecide(caller, approvalId, approved, comment, approver);
        } catch (SecurityException e) {
            LOG.warn("[dsh-approval] denied: {}", e.getMessage());
            return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound().body(Map.of("error", e.getMessage()));
        }
    }

    private HttpResponse<?> doDecide(DshIdentity.Principal caller, String approvalId, boolean approved, String comment, String requestedApprover) throws Exception {
        if (!isUuid(approvalId)) {
            return HttpResponse.badRequest().body(Map.of("error", "approvalId must be a valid UUID: " + approvalId));
        }
        // 服务身份 / admin 可携带决策人（Flow 的 approver 字段）；普通审批人恒为调用者 sub
        boolean privileged = caller.isService() || caller.isAdmin();
        String approver = privileged && requestedApprover != null && !requestedApprover.isBlank()
            ? requestedApprover : caller.sub();
        String payload = null;
        boolean effectiveApproval = false;
        try (Connection connection = open()) {
            String currentStatus;
            String sessionId;
            Instant createdAt;
            int timeoutSeconds;
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status, session_id::text, approvers, payload::text, created_at, timeout_seconds FROM dsh_approval WHERE id = ?::uuid")) {
                ps.setString(1, approvalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("dsh approval ticket not found: " + approvalId);
                    currentStatus = rs.getString(1);
                    sessionId = rs.getString(2);
                    payload = rs.getString(4);
                    Timestamp createdTs = rs.getTimestamp(5);
                    createdAt = createdTs == null ? Instant.now() : createdTs.toInstant();
                    timeoutSeconds = rs.getInt(6);
                    java.sql.Array approversArr = rs.getArray(3);
                    if (approversArr != null) {
                        List<String> approvers = List.of((String[]) approversArr.getArray());
                        if (!approvers.isEmpty() && !approvers.contains(approver) && !privileged) {
                            throw new SecurityException("approver " + approver + " is not in the allowed approvers list");
                        }
                    }
                }
            }
            if (!"PENDING".equals(currentStatus)) {
                throw new IllegalStateException("ticket is not PENDING (current: " + currentStatus + ")");
            }
            // dsh.docx 审批超时：过期的 PENDING 工单自动拒绝（与重构前 DshStore 语义一致）
            boolean expired = timeoutSeconds > 0
                && createdAt.plusSeconds(timeoutSeconds).isBefore(Instant.now());
            String status = expired || !approved ? "REJECTED" : "APPROVED";
            effectiveApproval = approved && !expired;
            // dsh.docx：审批唤醒是单事务原子操作——同一事务中更新审批状态和会话阶段
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE dsh_approval SET status = ?, approver = ?, comment = ?, decided_at = now() WHERE id = ?::uuid")) {
                ps.setString(1, status);
                ps.setString(2, approver);
                ps.setString(3, comment);
                ps.setString(4, approvalId);
                ps.executeUpdate();
            }
            if (effectiveApproval && sessionId != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE dsh_session SET phase = 'RUNNING', updated_at = now() WHERE id = ?::uuid AND phase = 'PENDING_APPROVAL'")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        }
        // dsh.docx 审批唤醒：会话恢复之外，同时唤醒挂起（PAUSED）的 Kestra 执行——
        // DshApproval CREATE 把 executionId 写进 payload，这里据此发 Resume 命令。
        if (effectiveApproval) {
            resumePausedExecution(payload);
        }
        return HttpResponse.ok(readRow(approvalId));
    }

    private Map<String, Object> readRow(String approvalId) throws Exception {
        String sql = """
            SELECT id::text, session_id::text, type, payload::text, approvers, status, approver, comment, timeout_seconds, created_at, decided_at
            FROM dsh_approval WHERE id = ?::uuid
            """;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, approvalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("dsh approval ticket not found: " + approvalId);
                return row(rs);
            }
        }
    }

    /** Best-effort resume of the paused execution referenced by the ticket payload. */
    private void resumePausedExecution(String payload) {
        try {
            String executionId = io.kestra.core.serializers.JacksonMapper
                .toMap(payload == null ? "{}" : payload).get("executionId") instanceof String s ? s : null;
            if (executionId == null) return;
            Execution execution = executionService.getExecutionIfPause(tenantService.resolveTenant(), executionId, true);
            executionCommandQueue.emit(Resume.from(execution, Pause.Resumed.now()));
            LOG.info("[dsh-approval] resumed paused execution {} after approval", executionId);
        } catch (Exception e) {
            // 工单不引用执行、或执行并未挂起（如已终态/超时路径）——按设计静默跳过
            LOG.debug("[dsh-approval] resume skipped: {}", e.getMessage());
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DshApprovalController.class);

    /** Read one ticket (admin, or an approver addressed on it, or unaddressed tickets). */
    @Get("/{approvalId}")
    @Operation(summary = "Read one dsh approval ticket (approver-scoped)")
    public HttpResponse<?> read(
        HttpRequest<?> request,
        @Parameter(description = "Ticket id") String approvalId
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "authentication required"));
        if (!isUuid(approvalId)) {
            return HttpResponse.badRequest().body(Map.of("error", "approvalId must be a valid UUID: " + approvalId));
        }
        if (!(caller.isService() || caller.isAdmin())) {
            try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dsh_approval WHERE id = ?::uuid "
                    + "AND (approvers IS NULL OR cardinality(approvers) = 0 OR ? = ANY(approvers))")) {
                ps.setString(1, approvalId);
                ps.setString(2, caller.sub());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return HttpResponse.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("error", "ticket " + approvalId + " does not exist or is not addressed to you"));
                    }
                }
            }
        }
        return HttpResponse.ok(readRow(approvalId));
    }

    private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("approvalId", rs.getString(1));
        row.put("sessionId", rs.getString(2));
        row.put("type", rs.getString(3));
        row.put("payload", rs.getString(4));
        java.sql.Array approversArr = rs.getArray(5);
        row.put("approvers", approversArr == null ? List.of() : List.of((String[]) approversArr.getArray()));
        row.put("status", rs.getString(6));
        row.put("approver", rs.getString(7));
        row.put("comment", rs.getString(8));
        row.put("timeoutSeconds", rs.getInt(9));
        row.put("createdAt", rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant().toString());
        row.put("decidedAt", rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant().toString());
        return row;
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
