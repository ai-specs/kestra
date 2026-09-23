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
 * Widen {@code dsh_metrics.session_id} from uuid to text.
 *
 * <p>
 * Phone-side dsh generates session ids that are plain business identifiers
 * (e.g. {@code session-<base36>}, e2e {@code e2e-new-<ts>}); a uuid column rejects
 * them with {@code invalid input syntax for type uuid}, and even uuid-shaped ids
 * fail once the insert no longer casts ({@code ?::uuid} removed upstream when the
 * column was meant to be text). This migration relaxes the column.
 *
 * <p>
 * Historical gap (2026-09-23): the 2.0.31 SQL resource existed since 2026-09-15 but
 * no {@code MigrationScript} bean ever registered it — the class was missing. The
 * migration therefore never ran, {@code dsh_metrics.session_id} stayed uuid, and
 * {@code POST /api/v1/dsh/metrics} kept failing with
 * {@code column "session_id" is of type uuid but expression is of type character
 * varying}. This class closes the gap; the SQL itself is idempotent
 * ({@code ALTER TYPE text} on an already-text column is a no-op).
 *
 * <p>
 * This bean deliberately does <strong>not</strong> extend
 * {@code AbstractSQLMigrationScript}: same rationale as the sibling dsh migrations
 * (the {@code :jdbc} base class must not be pulled into the webserver). It
 * implements {@link MigrationScript} directly and runs the resource via the shared
 * {@link SqlScriptSplitter}.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_31DshMetricsSessionIdTextMigration implements MigrationScript {

    private static final String SQL_RESOURCE = "/migrations/2.0.31-dsh-metrics-session-id-text-postgres.sql";

    private final DataSource dataSource;

    @Inject
    public V2_0_31DshMetricsSessionIdTextMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.31-dsh-metrics-session-id-text";
    }

    @Override
    public String description() {
        return "dsh_metrics.session_id uuid -> text (phone-generated session ids are not uuid-shaped)";
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
            cl = V2_0_31DshMetricsSessionIdTextMigration.class.getClassLoader();
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
