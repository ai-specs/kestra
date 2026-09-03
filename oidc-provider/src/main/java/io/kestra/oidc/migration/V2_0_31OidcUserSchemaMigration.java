package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Creates the OIDC user directory ({@code oidc_user}) and per-user authentication
 * methods ({@code oidc_user_auth_method}), modelled on ZITADEL's user domain
 * (users14 + humans child + user_auth_methods5) adapted to a single tenant.
 *
 * <p>
 * Seeding is deliberately not part of this migration: passwords must be bcrypt-hashed
 * at runtime, so {@code OidcUserService} bootstrap seeds the configured accounts
 * ({@code kestra.oidc.admin-username}/{@code kestra.oidc.users}) when the table is empty.
 * The tables are plain relational (no event sourcing), mechanism columns such as
 * {@code sequence}/{@code instance_id}/{@code resource_owner} are omitted — they only
 * serve ZITADEL's event replay and would be recreated by ZITADEL on any future import.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_31OidcUserSchemaMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_31OidcUserSchemaMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.31-oidc-user-schema";
    }

    @Override
    public String description() {
        return "OIDC user directory + authentication methods (ZITADEL-aligned, single tenant)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.31-oidc-user-schema.sql");
    }
}
