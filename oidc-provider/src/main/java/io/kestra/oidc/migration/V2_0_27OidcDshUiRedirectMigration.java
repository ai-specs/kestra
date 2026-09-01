package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Registers the dsh-ui (mobile) callback on the {@code dsh} OIDC client.
 *
 * <p>
 * dsh-ui is a browser application: it logs in through the authorization-code flow (client
 * {@code dsh}) with its BFF callback {@code /auth/callback} as the redirect target, then calls
 * the dsh APIs with the provider-issued access token.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_27OidcDshUiRedirectMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_27OidcDshUiRedirectMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.27-oidc-dsh-ui-redirect";
    }

    @Override
    public String description() {
        return "Register the dsh-ui BFF callback on the dsh OIDC client";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.27-oidc-dsh-ui-redirect.sql");
    }
}
