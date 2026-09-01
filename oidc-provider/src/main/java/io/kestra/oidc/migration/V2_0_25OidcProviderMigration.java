package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * PostgreSQL migration for the OIDC/OAuth2 Provider schema.
 *
 * <p>
 * Creates the {@code oidc_client}, {@code oidc_authorization_code}, {@code oidc_token} and
 * {@code oidc_jwk} tables and seeds the default clients (nacos / dsh / kestra-self) plus a
 * default RSA-2048 signing key (RS256).
 *
 * <p>
 * Registered through the Kestra migration mechanism: this bean is picked up by the
 * {@code MigrationRunner}, executed in lexical order by {@link #scriptId()} after all upstream
 * {@code 2.0.x} scripts.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_25OidcProviderMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_25OidcProviderMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.25-oidc-provider";
    }

    @Override
    public String description() {
        return "OIDC/OAuth2 Provider schema: oidc_client, oidc_authorization_code, oidc_token, oidc_jwk + seed data";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.25-oidc-provider-postgres.sql");
    }
}
