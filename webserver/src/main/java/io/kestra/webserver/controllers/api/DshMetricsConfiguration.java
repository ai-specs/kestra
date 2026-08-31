package io.kestra.webserver.controllers.api;

import io.micronaut.context.annotation.Singleton;
import io.micronaut.context.env.PropertyResolver;

import java.util.Optional;

/**
 * Connection settings for the dsh observation center queries. Defaults match
 * the dsh-monorepo docker-compose PostgreSQL; override via the
 * `dsh.metrics.jdbc-*` properties when the dsh tables live elsewhere.
 */
@Singleton
public class DshMetricsConfiguration {

    private final PropertyResolver resolver;

    public DshMetricsConfiguration(PropertyResolver resolver) {
        this.resolver = resolver;
    }

    public String jdbcUrl() {
        return resolver.getProperty("dsh.metrics.jdbc-url", String.class).orElse("jdbc:postgresql://postgres:5432/kestra");
    }

    public String username() {
        return resolver.getProperty("dsh.metrics.jdbc-username", String.class).orElse("kestra");
    }

    public String password() {
        return resolver.getProperty("dsh.metrics.jdbc-password", String.class).orElse("k3str4");
    }

    /** Escape hatch for callers that need Optional semantics. */
    public Optional<String> jdbcUrlOptional() {
        return resolver.getProperty("dsh.metrics.jdbc-url", String.class);
    }
}
