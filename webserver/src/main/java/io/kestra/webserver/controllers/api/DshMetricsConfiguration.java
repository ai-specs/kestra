package io.kestra.webserver.controllers.api;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Connection settings for the dsh observation center queries. The values are
 * provided by the deployment configuration (docker-compose KESTRA_CONFIGURATION:
 * dsh.metrics.jdbc-url / jdbc-username / jdbc-password) and must point at the
 * database holding the dsh_metrics table written by plugin-deepseek-harness.
 */
@Singleton
public class DshMetricsConfiguration {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DshMetricsConfiguration(
        @Value("${dsh.metrics.jdbc-url}") String jdbcUrl,
        @Value("${dsh.metrics.jdbc-username}") String username,
        @Value("${dsh.metrics.jdbc-password}") String password
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }
}
