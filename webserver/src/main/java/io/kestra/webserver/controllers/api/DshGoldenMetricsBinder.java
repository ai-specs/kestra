package io.kestra.webserver.controllers.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exposes the dsh golden indicators (dsh.docx 第十一章) as Micrometer gauges
 * with the `dsh_` prefix, refreshed from the dsh_metrics table every 30s and
 * scraped via the Prometheus endpoint.
 *
 * Gauges:
 * - dsh_golden_task_completion_rate (target >= 0.95)
 * - dsh_golden_tool_error_rate      (target <= 0.001)
 * - dsh_golden_p99_latency_ms       (target <= 500)
 * - dsh_tokens_total
 */
@Singleton
public class DshGoldenMetricsBinder {

    private final DshMetricsConfiguration configuration;
    private final AtomicReference<Double> completionRate = new AtomicReference<>(0d);
    private final AtomicReference<Double> toolErrorRate = new AtomicReference<>(0d);
    private final AtomicReference<Double> p99LatencyMs = new AtomicReference<>(0d);
    private final AtomicReference<Double> tokenTotal = new AtomicReference<>(0d);

    public DshGoldenMetricsBinder(DshMetricsConfiguration configuration, MeterRegistry registry) {
        this.configuration = configuration;
        Gauge.builder("dsh_golden_task_completion_rate", completionRate, ref -> ref.get())
            .description("dsh session task completion rate (golden target >= 0.95)")
            .register(registry);
        Gauge.builder("dsh_golden_tool_error_rate", toolErrorRate, ref -> ref.get())
            .description("dsh tool call error rate (golden target <= 0.001)")
            .register(registry);
        Gauge.builder("dsh_golden_p99_latency_ms", p99LatencyMs, ref -> ref.get())
            .description("dsh P99 latency in milliseconds (golden target <= 500)")
            .register(registry);
        Gauge.builder("dsh_tokens_total", tokenTotal, ref -> ref.get())
            .description("dsh total token consumption")
            .register(registry);
    }

    @Scheduled(fixedDelay = "30s", initialDelay = "5s")
    void refresh() {
        String sql = """
            SELECT count(*), coalesce(avg(task_completion_rate), 0), coalesce(avg(tool_error_rate), 0),
                   coalesce(percentile_disc(0.99) WITHIN GROUP (ORDER BY p99_latency_ms), 0),
                   coalesce(sum(token_usage), 0)
            FROM dsh_metrics WHERE created_at > now() - interval '24 hours'
            """;
        try (Connection connection = open(); PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    completionRate.set(rs.getDouble(2));
                    toolErrorRate.set(rs.getDouble(3));
                    p99LatencyMs.set(rs.getDouble(4));
                    tokenTotal.set(rs.getDouble(5));
                }
            }
        } catch (Exception ignored) {
            // degraded mode: keep previous gauge values when the database is unreachable
        }
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
