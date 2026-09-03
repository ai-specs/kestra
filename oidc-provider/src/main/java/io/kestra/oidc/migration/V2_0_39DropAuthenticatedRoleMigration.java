package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Drop the "authenticated" role.
 *
 * <p>
 * Machine identities were separated into pure OIDC clients in 2.0.36; their
 * roles now live on {@code oidc_client.roles}. The "authenticated" role was a
 * leftover from the machine-identity-as-user hack and is no longer meaningful
 * (no human user is assigned it; client_credentials tokens derive roles from
 * {@code oidc_client.roles}).
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_39DropAuthenticatedRoleMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_39DropAuthenticatedRoleMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.39-drop-authenticated-role";
    }

    @Override
    public String description() {
        return "Drop the 'authenticated' role (machine identities are OIDC clients)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.39-drop-authenticated-role.sql");
    }
}
