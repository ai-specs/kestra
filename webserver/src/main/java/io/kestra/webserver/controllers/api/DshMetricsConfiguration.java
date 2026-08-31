package io.kestra.webserver.controllers.api;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

/**
 * Connection settings for the dsh observation center queries. Defaults match
 * the dsh-monorepo docker-compose PostgreSQL; override via properties when the
 * dsh tables live elsewhere.
 */
@Singleton
public class DshMetricsConfiguration {

    @Property(name = "dsh.metrics.jdbc-url")
    private String jdbcUrl;

    @Property(name = "dsh.metrics.jdbc-username")
    private String username;

    @Property(name = "dsh.metrics.jdbc-password")
    private String password;

    public String jdbcUrl() {
        return jdbcUrl != null ? jdbcUrl : "jdbc:postgresql://postgres:5432/kestra";
    }

    public String username() {
        return username != null ? username : "kestra";
    }

    public String password() {
        return password != null ? password : "k3str4";
    }
}
