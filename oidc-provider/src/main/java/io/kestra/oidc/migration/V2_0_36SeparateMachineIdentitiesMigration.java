package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Separate machine identities (OIDC clients) from human users.
 *
 * <p>
 * nacos / dsh / kestra-self were stored as {@code oidc_user} rows
 * (type=machine) and bound to roles via {@code oidc_role_assignment}, so they
 * appeared in the user directory and role assignment UI. But they are really
 * OIDC clients (Applications) — their roles belong to the client, not to a
 * pseudo-user.
 *
 * <p>
 * This migration:
 * <ol>
 *   <li>Adds a {@code roles} JSONB column to {@code oidc_client} —
 *       client-scoped roles for the client_credentials grant.</li>
 *   <li>Migrates each machine identity's project roles into its client row.</li>
 *   <li>Deletes machine identity rows from {@code oidc_user} (cascading their
 *       role assignments).</li>
 * </ol>
 *
 * <p>
 * After this migration, the client_credentials flow reads roles directly from
 * the client row instead of doing a user-directory lookup.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_36SeparateMachineIdentitiesMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_36SeparateMachineIdentitiesMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.36-separate-machine-identities";
    }

    @Override
    public String description() {
        return "Separate machine identities (OIDC clients) from human users; add oidc_client.roles for client_credentials grant";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.36-separate-machine-identities.sql");
    }
}
