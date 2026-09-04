package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Backfills project-scoped role assignments from the legacy {@code oidc_user.roles}
 * column for accounts seeded by the runtime bootstrap after 2.0.35 ran.
 *
 * <p>
 * 2.0.35 made {@code oidc_role_assignment} the authoritative role source, but the
 * runtime bootstrap ({@code seedConfiguredAccounts}) only wrote the legacy column:
 * on a fresh install the migration ran before any user existed, so every seeded
 * account — including the admin — resolves to zero roles and the user/role
 * management surface deadlocks (admin role required to grant the admin role).
 * Merge-style and idempotent: existing assignments are untouched.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_40OidcRoleAssignmentBackfillMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_40OidcRoleAssignmentBackfillMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.40-oidc-role-assignment-backfill";
    }

    @Override
    public String description() {
        return "backfill dsh project role assignments for runtime-seeded accounts";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.40-oidc-role-assignment-backfill.sql");
    }
}
