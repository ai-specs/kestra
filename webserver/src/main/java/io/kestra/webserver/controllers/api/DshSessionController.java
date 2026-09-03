package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.Put;
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
import java.util.Set;

/**
 * Inbound session-sync API for dsh (PC) and dsh-ui (mobile)
 * (dsh.docx: dsh(PC) ←会话同步→ Kestra; dsh-ui ←会话查看/输入→ Kestra).
 *
 * dsh(PC) pushes session snapshots (PUT); dsh-ui reads the same snapshot (GET) and queues
 * inputs for the PC to consume (POST /input → PC POST /input/consume). The phase transition is
 * validated against the dsh.docx state machine.
 *
 * Session ownership (dsh.docx 跨端同步原理): every record is bound to the OAuth2 caller's
 * {@code sub} at creation. Reads, lists, updates and inputs are authorized by that owner —
 * the same user's PC and phone sync through the shared record; different users see nothing
 * of each other. Service identities (client_credentials, sub = client_id) own their own records.
 */
@Controller("/api/v1/dsh/sessions")
@ExecuteOn(TaskExecutors.IO)
public class DshSessionController {

    private static final java.util.regex.Pattern UUID_PATTERN =
        java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", 2);

    private static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value.toLowerCase()).matches();
    }

    private static final Set<String> PHASES = Set.of("CREATED", "RUNNING", "PENDING_APPROVAL", "COMPLETED", "FAILED");

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "CREATED", Set.of("RUNNING", "FAILED"),
        "RUNNING", Set.of("PENDING_APPROVAL", "COMPLETED", "FAILED"),
        "PENDING_APPROVAL", Set.of("RUNNING", "FAILED"),
        "COMPLETED", Set.of(),
        "FAILED", Set.of()
    );

    @Inject
    private DshMetricsConfiguration configuration;

    /**
     * Body of the state-only update: replaces the session state snapshot without touching the phase.
     * {@code state} accepts either a JSON string or a JSON object (serialized on the fly).
     */
    public record SessionState(Object state) {}

    /**
     * Body of a session snapshot upsert. {@code state} / {@code metadata} accept either a JSON
     * string (the Worker plugin path) or a raw JSON object (convenience for dsh PC / curl), which
     * is serialized back to a JSON string before the {@code ?::jsonb} cast — so both payload shapes
     * hit the same storage and the DB never rejects them with a deserialization 422.
     */
    public record SessionSnapshot(String sessionId, String phase, Object state, Object metadata,
                                  String userId, String at) {}

    public record SessionInput(String text) {}

    /** Normalize a JSON-typed field ({@code String} or {@code Map}/{@code List}) to a JSON string. */
    private static String jsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        try {
            return io.kestra.core.serializers.JacksonMapper.ofJson().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("state/metadata must be a JSON object or a JSON string", e);
        }
    }

    /**
     * Upsert a dsh session snapshot pushed by dsh (PC) or written by the Worker plugin
     * (DshSession / AIAgent tasks). The record's owner is the authenticated caller (OIDC sub) —
     * the payload cannot choose it — EXCEPT for service identities: a service identity may
     * create/update a session on behalf of an arbitrary user (the Worker plugin runs as the
     * {@code dsh} service identity and writes sessions owned by the Flow-supplied userId,
     * preserving the pre-refactor DshStore semantics). An existing record owned by someone else
     * is rejected with 403 for non-service callers.
     *
     * @param sessionId the dsh session id
     * @param snapshot phase / state / metadata / userId payload
     */
    @Put("/{sessionId}")
    @Operation(summary = "Upsert a dsh session snapshot (dsh PC / Worker plugin → Kestra session sync, owner-bound)")
    public HttpResponse<Map<String, Object>> upsert(
        HttpRequest<?> request,
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId,
        @Body SessionSnapshot snapshot
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return unauthorized();
        if (!isUuid(sessionId)) {
            return HttpResponse.badRequest(Map.of("error", "sessionId must be a valid UUID: " + sessionId));
        }
        String phase = snapshot.phase() == null ? "RUNNING" : snapshot.phase().toUpperCase();
        if (!PHASES.contains(phase)) {
            throw new IllegalArgumentException("unknown phase: " + phase);
        }
        boolean privileged = caller.isService();
        // The owner a session is bound to: the privileged caller may bind it to the payload's
        // userId (Worker plugin on behalf of a Flow user); everyone else is bound to their own sub.
        String owner = privileged && snapshot.userId() != null && !snapshot.userId().isBlank()
            ? snapshot.userId() : caller.sub();
        String state = jsonString(snapshot.state());
        String metadata = jsonString(snapshot.metadata());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String current = null;
            String existingOwner = null;
            try (PreparedStatement find = connection.prepareStatement("SELECT phase, owner FROM dsh_session WHERE id = ?::uuid FOR UPDATE")) {
                find.setString(1, sessionId);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getString(1);
                        existingOwner = rs.getString(2);
                    }
                }
            }
            if (current == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO dsh_session (id, user_id, owner, phase, state, metadata) VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb)")) {
                    insert.setString(1, sessionId);
                    insert.setString(2, snapshot.userId());
                    insert.setString(3, owner);
                    insert.setString(4, phase);
                    insert.setString(5, state == null ? "{}" : state);
                    insert.setString(6, metadata);
                    insert.executeUpdate();
                }
            } else {
                if (existingOwner != null && !existingOwner.equals(caller.sub()) && !privileged) {
                    connection.rollback();
                    return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "session " + sessionId + " is owned by another user"));
                }
                Set<String> allowed = TRANSITIONS.getOrDefault(current, Set.of());
                if (!allowed.contains(phase) && !current.equals(phase)) {
                    connection.rollback();
                    throw new IllegalArgumentException("illegal dsh session phase transition: " + current + " -> " + phase);
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE dsh_session SET phase = ?, state = coalesce(?::jsonb, state), metadata = coalesce(?::jsonb, metadata), owner = coalesce(owner, ?), updated_at = now() WHERE id = ?::uuid")) {
                    update.setString(1, phase);
                    update.setString(2, state);
                    update.setString(3, metadata);
                    update.setString(4, owner);
                    update.setString(5, sessionId);
                    update.executeUpdate();
                }
            }
            connection.commit();
        }
        return HttpResponse.ok(readOwned(request, sessionId));
    }

    /** Read the current session snapshot — only the owner (or a service identity) may read it. */
    @Get("/{sessionId}")
    @Operation(summary = "Read a dsh session snapshot (owner-scoped)")
    public HttpResponse<Map<String, Object>> readRoute(
        HttpRequest<?> request,
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return unauthorized();
        try {
            return HttpResponse.ok(readOwned(request, sessionId));
        } catch (OwnershipException e) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List dsh sessions, newest first (dsh-ui session browser + Worker DshSession LIST /
     * DshSessionTrigger poll). Cross-user isolation: every human caller (including admin) only
     * ever sees their own sessions (WHERE clause is the authenticated sub, not a query
     * parameter). A service identity may filter by any {@code userId} (and recency) — the
     * Worker plugin lists on behalf of a Flow-supplied user id, preserving the pre-refactor
     * DshStore {@code listSessions} semantics.
     */
    @Get
    @Operation(summary = "List dsh sessions (human callers: owner-scoped; service: optional userId/sinceHours filters)")
    public HttpResponse<List<Map<String, Object>>> list(
        HttpRequest<?> request,
        @Parameter(description = "Filter by phase") @QueryValue(defaultValue = "") String phase,
        @Parameter(description = "Max rows") @QueryValue(defaultValue = "50") int limit,
        @Parameter(description = "Filter by user id (service tokens only)") @QueryValue(defaultValue = "") String userId,
        @Parameter(description = "Only sessions updated within the last N hours (service; 0 = all)") @QueryValue(defaultValue = "0") int sinceHours
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return HttpResponse.unauthorized();
        boolean privileged = caller.isService();
        StringBuilder sql = new StringBuilder(
            "SELECT id::text, user_id, phase, state::text, metadata::text, created_at, updated_at, owner, pending_input, input_at "
                + "FROM dsh_session WHERE ");
        List<Object> params = new ArrayList<>();
        if (privileged) {
            sql.append("1=1");
            if (userId != null && !userId.isBlank()) { sql.append(" AND user_id = ?"); params.add(userId); }
        } else {
            sql.append("owner = ?");
            params.add(caller.sub());
        }
        if (phase != null && !phase.isBlank()) { sql.append(" AND phase = ?"); params.add(phase.toUpperCase()); }
        if (sinceHours > 0) { sql.append(" AND updated_at > now() - (? || ' hours')::interval"); params.add(String.valueOf(sinceHours)); }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object value : params) {
                if (value instanceof Integer intVal) ps.setInt(i++, intVal);
                else ps.setString(i++, (String) value);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(row(rs));
            }
        }
        return HttpResponse.ok(rows);
    }

    /**
     * Queue a mobile input on a session (dsh-ui → Kestra). Per dsh.docx PC 离线行为: the input
     * is stored and marked pending — NO reply is produced until dsh(PC) is back online and
     * consumes it. Allowed on the caller's own sessions in any phase.
     */
    @Post("/{sessionId}/input")
    @Operation(summary = "Queue a mobile input on a session (dsh-ui → PC pending input)")
    public HttpResponse<Map<String, Object>> input(
        HttpRequest<?> request,
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId,
        @Body SessionInput body
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return unauthorized();
        if (!isUuid(sessionId)) {
            return HttpResponse.badRequest(Map.of("error", "sessionId must be a valid UUID: " + sessionId));
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "text is required"));
        }
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "UPDATE dsh_session SET pending_input = ?, input_at = now(), updated_at = now() "
                + "WHERE id = ?::uuid AND owner = ?")) {
            ps.setString(1, body.text());
            ps.setString(2, sessionId);
            ps.setString(3, caller.sub());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "session " + sessionId + " does not exist or is owned by another user"));
            }
        }
        return HttpResponse.ok(readOwned(request, sessionId));
    }

    /**
     * Atomically consume the pending input (dsh(PC) poll → resume execution). Single UPDATE ...
     * RETURNING so two concurrent consumers can never both receive the text.
     */
    @Post("/{sessionId}/input/consume")
    @Operation(summary = "Atomically consume the pending input (dsh PC → resume execution)")
    public HttpResponse<Map<String, Object>> consumeInput(
        HttpRequest<?> request,
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return unauthorized();
        if (!isUuid(sessionId)) {
            return HttpResponse.badRequest(Map.of("error", "sessionId must be a valid UUID: " + sessionId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        // 单语句原子消费：CTE 先 FOR UPDATE 锁行并读旧值，UPDATE 清除后仅当清到的行存在才返回
        //（UPDATE..RETURNING 给的是新值（NULL），拿不到旧输入，所以必须经 target CTE）。
        String sql = """
            WITH target AS (
                SELECT id, pending_input, input_at FROM dsh_session
                WHERE id = ?::uuid AND owner = ? AND pending_input IS NOT NULL
                LIMIT 1
                FOR UPDATE
            ), upd AS (
                UPDATE dsh_session SET pending_input = NULL, input_at = NULL, updated_at = now()
                WHERE id IN (SELECT id FROM target)
                RETURNING id
            )
            SELECT t.pending_input, t.input_at FROM target t JOIN upd ON upd.id = t.id
            """;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, caller.sub());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.put("text", rs.getString(1));
                    result.put("at", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
                } else {
                    result.put("text", null);
                }
            }
        }
        return HttpResponse.ok(result);
    }

    /**
     * Replace the session state snapshot WITHOUT a phase transition (Worker DshStore.updateState /
     * AIAgent trace timeline). The phase state machine does not apply here — only state is
     * rewritten. Owner check mirrors the read path (service identities may write any session).
     */
    @Post("/{sessionId}/state")
    @Operation(summary = "Replace the session state snapshot without touching the phase")
    public HttpResponse<Map<String, Object>> updateState(
        HttpRequest<?> request,
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId,
        @Body SessionState body
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) return unauthorized();
        if (!isUuid(sessionId)) {
            return HttpResponse.badRequest(Map.of("error", "sessionId must be a valid UUID: " + sessionId));
        }
        if (body == null) {
            return HttpResponse.badRequest(Map.of("error", "state is required"));
        }
        String state = jsonString(body.state());
        if (state == null) {
            return HttpResponse.badRequest(Map.of("error", "state is required"));
        }
        boolean privileged = caller.isService();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "UPDATE dsh_session SET state = ?::jsonb, updated_at = now() "
                + "WHERE id = ?::uuid AND (owner = ? OR ?)")) {
            ps.setString(1, state);
            ps.setString(2, sessionId);
            ps.setString(3, caller.sub());
            ps.setBoolean(4, privileged);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "session " + sessionId + " does not exist or is owned by another user"));
            }
        }
        return HttpResponse.ok(readOwned(request, sessionId));
    }

    /** Shared read with ownership enforcement (service identity may read any session). */
    private Map<String, Object> readOwned(HttpRequest<?> request, String sessionId) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "SELECT id::text, user_id, phase, state::text, metadata::text, created_at, updated_at, owner, pending_input, input_at "
                + "FROM dsh_session WHERE id = ?::uuid")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("dsh session not found: " + sessionId);
                Map<String, Object> row = row(rs);
                String owner = (String) row.get("owner");
                if (caller != null && owner != null && !owner.equals(caller.sub()) && !caller.isService()) {
                    throw new OwnershipException(sessionId);
                }
                return row;
            }
        }
    }

    /** Marker exception mapped to 403 by the Kestra error handling (message-safe, no data leak). */
    public static class OwnershipException extends SecurityException {
        public OwnershipException(String sessionId) {
            super("session " + sessionId + " is owned by another user");
        }
    }

    private static HttpResponse<Map<String, Object>> unauthorized() {
        return HttpResponse.unauthorized();
    }

    private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", rs.getString(1));
        row.put("userId", rs.getString(2));
        row.put("phase", rs.getString(3));
        row.put("state", rs.getString(4));
        row.put("metadata", rs.getString(5));
        row.put("createdAt", rs.getTimestamp(6).toInstant().toString());
        row.put("updatedAt", rs.getTimestamp(7).toInstant().toString());
        row.put("owner", rs.getString(8));
        row.put("pendingInput", rs.getString(9));
        row.put("inputAt", rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant().toString());
        return row;
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
