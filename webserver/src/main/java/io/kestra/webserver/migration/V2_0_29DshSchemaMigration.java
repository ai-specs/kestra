package io.kestra.webserver.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import io.kestra.core.migration.MigrationScript;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * PostgreSQL migration for the dsh observation tables
 * ({@code dsh_session} / {@code dsh_approval} / {@code dsh_metrics}).
 *
 * <p>
 * The dsh schema was historically bootstrapped from the Worker JVM (plugin-deepseek-harness
 * {@code DshStore.ensureSchema}) over a raw JDBC connection, then from the webserver-side
 * {@code DshSchemaInitializer} startup listener. Both are now replaced by this versioned
 * migration, aligning the dsh tables with the Kestra migration system: they get the same
 * distributed lock, history table and checksum verification as every other schema, and they are
 * created <em>before</em> any repository/service bean touches the database.
 *
 * <p>
 * This bean deliberately does <strong>not</strong> extend {@code AbstractSQLMigrationScript}:
 * that base class lives in the {@code :jdbc} module whose dependencies (worker/executor/jOOQ)
 * must not be pulled into the webserver. It implements {@link MigrationScript} directly and runs
 * the resource with a small statement splitter (single-quoted strings, {@code $$...$$} blocks,
 * line/block comments); it borrows the {@code DelegatingDataSource.unwrapDataSource()} trick from
 * the jdbc module so a real connection is obtained outside any {@code @Connectable} context.
 *
 * <p>
 * Migrations run on the control plane only ({@code MigrationStartupRunner} excludes WORKER
 * server types), which matches the 2.0 architecture: workers hold no database credentials and
 * never migrate the schema. The resource is the authoritative copy of the dsh DDL and must stay
 * structurally compatible with the pre-migration schema (idempotent: old databases created by the
 * legacy two-phase {@code ensureSchema} are upgraded in place via
 * {@code ADD COLUMN IF NOT EXISTS} + an {@code owner = user_id} backfill).
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_29DshSchemaMigration implements MigrationScript {

    private static final String SQL_RESOURCE = "/migrations/2.0.29-dsh-schema-postgres.sql";

    private final DataSource dataSource;

    @Inject
    public V2_0_29DshSchemaMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.29-dsh-schema";
    }

    @Override
    public String description() {
        return "dsh observation schema: dsh_session, dsh_approval, dsh_metrics";
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
        // Migrations run during startup, outside any @Connectable/transaction context, so the
        // injected DataSource is a Micronaut Data AOP proxy. Unwrap it (same as the jdbc module's
        // AbstractSQLMigrationScript) to obtain a real connection without a connection context.
        try (Connection connection = DelegatingDataSource.unwrapDataSource(dataSource).getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                for (String statementSql : splitStatements(sql)) {
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
            cl = V2_0_29DshSchemaMigration.class.getClassLoader();
        }
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream is = cl.getResourceAsStream(normalized)) {
            if (is == null) {
                throw new IllegalArgumentException("SQL resource not found on classpath: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits a SQL script into individual statements, ignoring {@code ;} inside single-quoted
     * string literals and PostgreSQL dollar-quoted blocks, and dropping line/block comments.
     */
    static List<String> splitStatements(final String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDollar = false;
        String dollarTag = null;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (!inSingleQuote && !inDollar && c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (!inSingleQuote && !inDollar && c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (!inDollar && c == '\'') {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                i++;
                continue;
            }
            if (!inSingleQuote && !inDollar && c == '$') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    inDollar = true;
                    dollarTag = sql.substring(i, j + 1);
                    current.append(dollarTag);
                    i = j + 1;
                    continue;
                }
            }
            if (inDollar && sql.startsWith(dollarTag, i)) {
                inDollar = false;
                current.append(dollarTag);
                i += dollarTag.length();
                continue;
            }
            if (!inSingleQuote && !inDollar && c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }
}
