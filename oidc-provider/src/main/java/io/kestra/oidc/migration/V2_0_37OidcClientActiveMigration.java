package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Add active flag to oidc_client (standard OAuth2 client enable/disable).
 *
 * <p>
 * Allows enabling/disabling an OIDC client (machine identity / service account).
 * An inactive client is refused at the token endpoint (client_credentials grant).
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_37OidcClientActiveMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_37OidcClientActiveMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.37-oidc-client-active";
    }

    @Override
    public String description() {
        return "Add active flag to oidc_client (standard OAuth2 client enable/disable)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.37-oidc-client-active.sql");
    }
}
