package io.kestra.webserver.controllers.api;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
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
 * (dsh.docx: dsh(PC) ←会话同步→ Kestra; dsh-ui ←会话查看→ Kestra).
 *
 * dsh(PC) pushes session snapshots (PUT); dsh-ui reads the same snapshot (GET).
 * The phase transition is validated against the dsh.docx state machine.
 */
@Controller("/api/v1/dsh/sessions")
@ExecuteOn(TaskExecutors.IO)
public class DshSessionController {

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

    public record SessionSnapshot(String sessionId, String phase, String state, String metadata,
                                  String userId, String at) {}

    /**
     * Upsert a dsh session snapshot pushed by dsh (PC).
     *
     * @param sessionId the dsh session id
     * @param snapshot phase / state / metadata / userId payload
     */
    @Put("/{sessionId}")
    @Operation(summary = "Upsert a dsh session snapshot (dsh PC → Kestra session sync)")
    public Map<String, Object> upsert(
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId,
        @Body SessionSnapshot snapshot
    ) throws Exception {
        String phase = snapshot.phase() == null ? "RUNNING" : snapshot.phase().toUpperCase();
        if (!PHASES.contains(phase)) {
            throw new IllegalArgumentException("unknown phase: " + phase);
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            String current = null;
            try (PreparedStatement find = connection.prepareStatement("SELECT phase FROM dsh_session WHERE id = ?::uuid FOR UPDATE")) {
                find.setString(1, sessionId);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) current = rs.getString(1);
                }
            }
            if (current == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO dsh_session (id, user_id, phase, state, metadata) VALUES (?::uuid, ?, ?, ?::jsonb, ?::jsonb)")) {
                    insert.setString(1, sessionId);
                    insert.setString(2, snapshot.userId());
                    insert.setString(3, phase);
                    insert.setString(4, snapshot.state() == null ? "{}" : snapshot.state());
                    insert.setString(5, snapshot.metadata());
                    insert.executeUpdate();
                }
            } else {
                Set<String> allowed = TRANSITIONS.getOrDefault(current, Set.of());
                if (!allowed.contains(phase) && !current.equals(phase)) {
                    connection.rollback();
                    throw new IllegalArgumentException("illegal dsh session phase transition: " + current + " -> " + phase);
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE dsh_session SET phase = ?, state = coalesce(?::jsonb, state), metadata = coalesce(?::jsonb, metadata), updated_at = now() WHERE id = ?::uuid")) {
                    update.setString(1, phase);
                    update.setString(2, snapshot.state());
                    update.setString(3, snapshot.metadata());
                    update.setString(4, sessionId);
                    update.executeUpdate();
                }
            }
            connection.commit();
        }
        return read(sessionId);
    }

    /** Read the current session snapshot (dsh-ui ←会话查看). */
    @Get("/{sessionId}")
    @Operation(summary = "Read a dsh session snapshot")
    public Map<String, Object> read(
        @Parameter(description = "The dsh session id") @PathVariable("sessionId") String sessionId
    ) throws Exception {
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "SELECT id::text, user_id, phase, state::text, metadata::text, created_at, updated_at FROM dsh_session WHERE id = ?::uuid")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("dsh session not found: " + sessionId);
                return row(rs);
            }
        }
    }

    /** List recent sessions (optionally filtered by phase), newest first (dsh-ui session browser). */
    @Get
    @Operation(summary = "List recent dsh sessions")
    public List<Map<String, Object>> list(
        @Parameter(description = "Filter by phase") @QueryValue(defaultValue = "") String phase,
        @Parameter(description = "Max rows") @QueryValue(defaultValue = "50") int limit
    ) throws Exception {
        StringBuilder sql = new StringBuilder(
            "SELECT id::text, user_id, phase, state::text, metadata::text, created_at, updated_at FROM dsh_session WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (phase != null && !phase.isBlank()) { sql.append(" AND phase = ?"); params.add(phase.toUpperCase()); }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        params.add(Math.min(Math.max(limit, 1), 500));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object value = params.get(i);
                if (value instanceof Integer intVal) ps.setInt(i + 1, intVal);
                else ps.setString(i + 1, (String) value);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(row(rs));
            }
        }
        return rows;
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
        return row;
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
