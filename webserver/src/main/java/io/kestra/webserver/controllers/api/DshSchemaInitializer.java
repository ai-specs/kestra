package io.kestra.webserver.controllers.api;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent bootstrap of the dsh tables (dsh_session / dsh_approval / dsh_metrics) on the
 * webserver side.
 *
 * <p>
 * Kestra 2.0 architecture: workers hold no database credentials and never touch the repository —
 * every data access goes through the control plane. The dsh schema was historically bootstrapped
 * from the Worker JVM (plugin-deepseek-harness {@code DshStore.ensureSchema}) over a raw JDBC
 * connection; after the DshStore HTTP refactor the bootstrap moves here, next to the Dsh*
 * controllers that own the data access. The DDL below is the authoritative copy of
 * {@code migrations/dsh-schema-postgres.sql} (plugin) and must stay byte-for-byte compatible —
 * no table/column changes, only idempotent creation.
 *
 * <p>
 * Startup is tolerant of a not-yet-ready database (same contract as
 * {@link DshMetricsController#ensureSchema}): a transient failure only logs a warning and the
 * controllers report a clean empty payload until the migration catches up.
 */
@Singleton
@Requires(property = "dsh.metrics.jdbc-url")
public class DshSchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(DshSchemaInitializer.class);

    private static final List<String> DDL = List.of(
        // dsh_session — owner/pending_input/input_at are idempotently added below for old DBs
        """
        CREATE TABLE IF NOT EXISTS dsh_session (
            id          UUID PRIMARY KEY,
            user_id     TEXT,
            phase       TEXT NOT NULL,
            state       JSONB,
            metadata    JSONB,
            created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        "ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS owner TEXT",
        "ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS pending_input TEXT",
        "ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS input_at TIMESTAMPTZ",
        "CREATE INDEX IF NOT EXISTS idx_dsh_session_user  ON dsh_session(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_dsh_session_phase ON dsh_session(phase, updated_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_dsh_session_owner ON dsh_session(owner, updated_at DESC)",
        // legacy rows predating the owner column inherit the user id (dsh.docx 跨端同步兜底)
        "UPDATE dsh_session SET owner = user_id WHERE owner IS NULL",
        """
        CREATE TABLE IF NOT EXISTS dsh_approval (
            id              UUID PRIMARY KEY,
            session_id      UUID REFERENCES dsh_session(id),
            type            TEXT NOT NULL,
            payload         JSONB,
            approvers       TEXT[],
            status          TEXT NOT NULL,
            approver        TEXT,
            comment         TEXT,
            timeout_seconds INT NOT NULL DEFAULT 0,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            decided_at      TIMESTAMPTZ
        )""",
        "CREATE INDEX IF NOT EXISTS idx_dsh_approval_status ON dsh_approval(status, created_at DESC)",
        """
        CREATE TABLE IF NOT EXISTS dsh_metrics (
            id                   BIGSERIAL PRIMARY KEY,
            session_id           UUID,
            user_id              TEXT,
            task_completion_rate DOUBLE PRECISION,
            tool_error_rate      DOUBLE PRECISION,
            p99_latency_ms       BIGINT,
            token_usage          BIGINT,
            created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        "CREATE INDEX IF NOT EXISTS idx_dsh_metrics_session ON dsh_metrics(session_id, created_at DESC)"
    );

    private final DshMetricsConfiguration configuration;

    @Inject
    public DshSchemaInitializer(DshMetricsConfiguration configuration) {
        this.configuration = configuration;
    }

    @EventListener
    public void onStartup(StartupEvent event) {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String ddl : DDL) {
                statement.execute(ddl);
            }
            LOG.info("[dsh-schema] dsh_session / dsh_approval / dsh_metrics ready (idempotent)");
        } catch (Exception e) {
            // database warming up / table already owned by a pending migration: keep the
            // controllers on a stable empty-payload error surface (same contract as before)
            LOG.warn("[dsh-schema] bootstrap deferred: {}", e.getMessage());
        }
    }

    private Connection open() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(
            configuration.jdbcUrl(), configuration.username(), configuration.password());
    }
}
