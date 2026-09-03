package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dsh observation center API (dsh.docx 第十一章 量化评估).
 *
 * Aggregates the golden indicators written by the plugin-deepseek-harness
 * DshMetrics task into the dsh_metrics table:
 * - task completion rate (target >= 95%)
 * - tool call error rate (target <= 0.1%)
 * - P99 latency (target <= 500ms)
 */
@Controller("/api/v1/dsh/metrics")
@ExecuteOn(TaskExecutors.IO)
public class DshMetricsController {

    @Inject
    private DshMetricsConfiguration configuration;

    @PostConstruct
    void ensureSchema() {
        try (Connection connection = open()) {
            // touch the table so the first UI call has a stable error surface
            connection.prepareStatement("SELECT 1 FROM dsh_metrics LIMIT 1").executeQuery().close();
        } catch (Exception ignored) {
            // table missing / database warming up: endpoints report a clean empty payload
        }
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }

    /** One golden-indicator measurement reported by the Worker plugin (DshStore.insertMetrics). */
    public record MetricsReport(String sessionId, String userId, Double taskCompletionRate,
                                Double toolErrorRate, Long p99LatencyMs, Long tokenUsage) {}

    /**
     * Report one golden-indicator measurement into dsh_metrics (Worker plugin DshMetrics REPORT
     * and AIAgent). Authenticated dsh callers only; a null/0 P99 is stored as NULL (the summary
     * aggregates over non-null P99s, same as the pre-refactor JDBC insert).
     */
    @Post
    @Operation(summary = "Report one dsh golden-indicator measurement (Worker → observation center)")
    public HttpResponse<Map<String, Object>> report(
        HttpRequest<?> request,
        @Body MetricsReport body
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "authentication required"));
        }
        if (body == null) {
            return HttpResponse.badRequest(Map.of("error", "body is required"));
        }
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO dsh_metrics (session_id, user_id, task_completion_rate, tool_error_rate, p99_latency_ms, token_usage) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?) RETURNING id, created_at")) {
            ps.setString(1, body.sessionId());
            ps.setString(2, body.userId());
            ps.setDouble(3, body.taskCompletionRate() == null ? 0d : body.taskCompletionRate());
            ps.setDouble(4, body.toolErrorRate() == null ? 0d : body.toolErrorRate());
            Long p99 = body.p99LatencyMs() == null ? 0L : body.p99LatencyMs();
            if (p99 == 0) ps.setNull(5, Types.BIGINT); else ps.setLong(5, p99);
            ps.setLong(6, body.tokenUsage() == null ? 0L : body.tokenUsage());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", rs.getLong(1));
                result.put("createdAt", rs.getTimestamp(2).toInstant().toString());
                result.put("sessionId", body.sessionId());
                result.put("userId", body.userId());
                result.put("taskCompletionRate", body.taskCompletionRate() == null ? 0d : body.taskCompletionRate());
                result.put("toolErrorRate", body.toolErrorRate() == null ? 0d : body.toolErrorRate());
                result.put("p99LatencyMs", p99);
                result.put("tokenUsage", body.tokenUsage() == null ? 0L : body.tokenUsage());
                return HttpResponse.ok(result);
            }
        }
    }

    /**
     * Golden-indicator summary over the aggregation window.
     *
     * @param sinceHours window in hours; 0 means all history
     * @param userId optional owner filter
     */
    @Get("/summary")
    @Operation(summary = "dsh golden indicators summary (completion rate, tool error rate, P99, tokens)")
    public Map<String, Object> summary(
        HttpRequest<?> request,
        @Parameter(description = "Aggregation window in hours; 0 = all") @QueryValue(defaultValue = "0") int sinceHours,
        @Parameter(description = "Filter by owning user id (service tokens only)") @QueryValue(defaultValue = "") String userId
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        // 人类用户（含 admin）只看自己的数据（跨用户严格隔离）；服务身份可聚合全局或按 userId（观察中心 Worker）
        String ownerFilter = caller != null && !caller.isService()
            ? caller.sub() : (userId == null || userId.isBlank() ? null : userId);
        StringBuilder sql = new StringBuilder(
            "SELECT count(*), coalesce(avg(task_completion_rate), 0), coalesce(avg(tool_error_rate), 0), "
                + "coalesce(percentile_disc(0.99) WITHIN GROUP (ORDER BY p99_latency_ms), 0), coalesce(max(p99_latency_ms), 0), coalesce(sum(token_usage), 0) "
                + "FROM dsh_metrics WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (sinceHours > 0) { sql.append(" AND created_at > now() - (? || ' hours')::interval"); params.add(String.valueOf(sinceHours)); }
        if (ownerFilter != null) { sql.append(" AND user_id = ?"); params.add(ownerFilter); }
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, (String) params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long sessions = rs.getLong(1);
                double completion = rs.getDouble(2);
                double toolError = rs.getDouble(3);
                long p99 = (long) rs.getDouble(4);
                long worst = rs.getLong(5);
                long tokens = rs.getLong(6);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sessions", sessions);
                result.put("taskCompletionRate", completion);
                result.put("taskCompletionRatePercent", completion * 100);
                result.put("toolErrorRate", toolError);
                result.put("toolErrorRatePercent", toolError * 100);
                result.put("p99LatencyMs", p99);
                result.put("worstP99LatencyMs", worst);
                result.put("tokenUsageTotal", tokens);
                result.put("scopedTo", ownerFilter);
                result.put("targets", Map.of(
                    "taskCompletionRatePercent", 95.0,
                    "toolErrorRatePercent", 0.1,
                    "p99LatencyMs", 500
                ));
                result.put("targetsMet", completion >= 0.95 && toolError <= 0.001 && p99 <= 500);
                return result;
            }
        }
    }

    /**
     * Hourly time series of the golden indicators, suitable for dashboards.
     *
     * @param sinceHours window in hours; 0 means all history
     */
    @Get("/timeseries")
    @Operation(summary = "dsh golden indicators hourly time series")
    public List<Map<String, Object>> timeseries(
        HttpRequest<?> request,
        @Parameter(description = "Aggregation window in hours; 0 = all") @QueryValue(defaultValue = "24") int sinceHours
    ) throws Exception {
        DshIdentity.Principal caller = DshIdentity.of(request);
        String ownerFilter = caller != null && !caller.isService() ? caller.sub() : null;
        String sql = """
            SELECT date_trunc('hour', created_at) AS bucket,
                   count(*) AS measurements,
                   coalesce(avg(task_completion_rate), 0) AS completion,
                   coalesce(avg(tool_error_rate), 0) AS tool_error,
                   coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY p99_latency_ms), 0) AS p99,
                   coalesce(sum(token_usage), 0) AS tokens
            FROM dsh_metrics
            WHERE (? = 0 OR created_at > now() - (? || ' hours')::interval)
            """ + (ownerFilter != null ? "AND user_id = ?" : "") + """
            GROUP BY 1 ORDER BY 1
            """;
        List<Map<String, Object>> series = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, sinceHours);
            ps.setString(2, String.valueOf(sinceHours));
            if (ownerFilter != null) ps.setString(3, ownerFilter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("bucket", rs.getTimestamp(1).toInstant().toString());
                    point.put("measurements", rs.getLong(2));
                    point.put("taskCompletionRate", rs.getDouble(3));
                    point.put("toolErrorRate", rs.getDouble(4));
                    point.put("p99LatencyMs", (long) rs.getDouble(5));
                    point.put("tokenUsage", rs.getLong(6));
                    series.add(point);
                }
            }
        }
        return series;
    }
}
