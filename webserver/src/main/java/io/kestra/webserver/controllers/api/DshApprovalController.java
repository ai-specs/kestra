package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public DshApprovalController(DshMetricsConfiguration configuration) {
        this.configuration = configuration;
    }

    /** Approval inbox: list tickets by status (dsh-ui 审批收件箱). */
    @Get
    @Operation(summary = "List dsh approval tickets by status (default PENDING)")
    public List<Map<String, Object>> list(
        @Parameter(description = "Filter by status (PENDING/APPROVED/REJECTED)") @QueryValue(defaultValue = "PENDING") String status
    ) throws Exception {
        String sql = """
            SELECT id::text, session_id::text, type, payload::text, status, approver, comment, created_at, decided_at
            FROM dsh_approval WHERE status = ? ORDER BY created_at DESC LIMIT 200
            """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(row(rs));
            }
        }
        return rows;
    }

    /**
     * Decide a ticket (approve/reject) as a human approver via dsh-ui.
     * An approved decision resumes the owning session (pending_approval → running);
     * expired or already-decided tickets are rejected with 409/404 semantics.
     */
    @Post("/{approvalId}/decide")
    @Operation(summary = "Approve or reject a ticket; resumes the owning session on approval")
    public HttpResponse<?> decide(
        @Parameter(description = "Ticket id") String approvalId,
        @Parameter(description = "true = approve, false = reject") @QueryValue(defaultValue = "true") boolean approved,
        @Parameter(description = "Human approver id (dsh-ui user)") @QueryValue(defaultValue = "dsh-ui") String approver,
        @Parameter(description = "Decision comment") @QueryValue(defaultValue = "") String comment
    ) throws Exception {
        try {
            return doDecide(approvalId, approved, approver, comment);
        } catch (SecurityException e) {
            LOG.warn("[dsh-approval] denied: {}", e.getMessage());
            return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound().body(Map.of("error", e.getMessage()));
        }
    }

    private HttpResponse<?> doDecide(String approvalId, boolean approved, String approver, String comment) throws Exception {
        if (!isUuid(approvalId)) {
            return HttpResponse.badRequest().body(Map.of("error", "approvalId must be a valid UUID: " + approvalId));
        }
        try (Connection connection = open()) {
            String currentStatus;
            String sessionId;
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status, session_id::text, approvers FROM dsh_approval WHERE id = ?::uuid")) {
                ps.setString(1, approvalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("dsh approval ticket not found: " + approvalId);
                    currentStatus = rs.getString(1);
                    sessionId = rs.getString(2);
                    java.sql.Array approversArr = rs.getArray(3);
                    if (approversArr != null) {
                        List<String> approvers = List.of((String[]) approversArr.getArray());
                        if (!approvers.isEmpty() && !approvers.contains(approver)) {
                            throw new SecurityException("approver " + approver + " is not in the allowed approvers list");
                        }
                    }
                }
            }
            if (!"PENDING".equals(currentStatus)) {
                throw new IllegalStateException("ticket is not PENDING (current: " + currentStatus + ")");
            }
            // dsh.docx：审批唤醒是单事务原子操作——同一事务中更新审批状态和会话阶段
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE dsh_approval SET status = ?, approver = ?, comment = ?, decided_at = now() WHERE id = ?::uuid")) {
                ps.setString(1, approved ? "APPROVED" : "REJECTED");
                ps.setString(2, approver);
                ps.setString(3, comment);
                ps.setString(4, approvalId);
                ps.executeUpdate();
            }
            if (approved && sessionId != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE dsh_session SET phase = 'RUNNING', updated_at = now() WHERE id = ?::uuid AND phase = 'PENDING_APPROVAL'")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        }
        return HttpResponse.ok(read(approvalId));
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DshApprovalController.class);

    /** Read one ticket. */
    @Get("/{approvalId}")
    @Operation(summary = "Read one dsh approval ticket")
    public Map<String, Object> read(
        @Parameter(description = "Ticket id") String approvalId
    ) throws Exception {
        String sql = """
            SELECT id::text, session_id::text, type, payload::text, status, approver, comment, created_at, decided_at
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

    private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("approvalId", rs.getString(1));
        row.put("sessionId", rs.getString(2));
        row.put("type", rs.getString(3));
        row.put("payload", rs.getString(4));
        row.put("status", rs.getString(5));
        row.put("approver", rs.getString(6));
        row.put("comment", rs.getString(7));
        row.put("createdAt", rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant().toString());
        row.put("decidedAt", rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant().toString());
        return row;
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
