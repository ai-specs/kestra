package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Adds machine identities (service accounts) to the OIDC user directory.
 *
 * <p>
 * Complements {@code 2.0.31-oidc-user-schema} with ZITADEL's human/machine vertical split:
 * {@code oidc_user} becomes the unified identity main table with a {@code type}
 * discriminator (human/machine) and machine-specific fields move to the child table
 * {@code oidc_user_machine} (ZITADEL users14_machines). Existing client_credentials
 * clients are migrated as machine rows so their roles/state are persisted instead of
 * falling back to the configured default roles.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_32OidcMachineIdentitiesMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_32OidcMachineIdentitiesMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.32-oidc-machine-identities";
    }

    @Override
    public String description() {
        return "Machine identities (service accounts) in the OIDC user directory (ZITADEL human/machine split)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.32-oidc-machine-identities.sql");
    }
}
