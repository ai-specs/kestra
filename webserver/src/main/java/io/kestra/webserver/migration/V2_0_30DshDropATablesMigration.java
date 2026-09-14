/*
 * Copyright (C) 2026 dsh monorepo contributors.
 *
 * Migration: drop option A tables (dsh_session / dsh_approval).
 *
 * Option A (Kestra-side full session storage + PC polling consumption of
 * pending_input) was rejected in favor of option B (edge-authoritative PC +
 * Kestra pure relay) on 2026-09-14. A's components were retired stepwise
 * (controllers removed, endpoints 404, mirroring disabled, mid-platform writes
 * removed); this migration completes full retirement by dropping the A-only
 * tables. dsh_metrics (option B metrics aggregation) and dsh_secret (secrets
 * management) are retained.
 *
 * Idempotent: DROP TABLE IF EXISTS. Data was backed up before applying
 * (app-scripts/e2e/… backup, /tmp/dsh-a-tables-backup-*.sql).
 */
package io.kestra.webserver.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import io.kestra.core.migration.MigrationScript;
import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Drops the option A dsh tables (dsh_session, dsh_approval) after the option B
 * migration is fully accepted (roadmap #10/#11 + full-retirement step).
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_30DshDropATablesMigration implements MigrationScript {

    private static final String SQL_RESOURCE = "/migrations/2.0.30-dsh-drop-a-tables-postgres.sql";

    private final DataSource dataSource;

    @Inject
    public V2_0_30DshDropATablesMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.30-dsh-drop-a-tables";
    }

    @Override
    public String description() {
        return "drop option A dsh tables (dsh_session, dsh_approval) — full retirement";
    }

    @Override
    public String checksum() {
        return MigrationScript.checksumOfResources(SQL_RESOURCE);
    }

    @Override
    public List<String> sqlResources() {
        return List.of(SQL_RESOURCE);
    }

    @Override
    public void migrate() throws Exception {
        String sql = readResource(SQL_RESOURCE);
        try (Connection connection = DelegatingDataSource.unwrapDataSource(dataSource).getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                for (String statementSql : SqlScriptSplitter.splitStatements(sql)) {
                    if (!statementSql.isBlank()) {
                        statement.execute(statementSql);
                    }
                }
            }
        }
    }

    private static String readResource(final String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = V2_0_30DshDropATablesMigration.class.getClassLoader();
        }
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream is = cl.getResourceAsStream(normalized)) {
            if (is == null) {
                throw new IllegalArgumentException("SQL resource not found on classpath: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
