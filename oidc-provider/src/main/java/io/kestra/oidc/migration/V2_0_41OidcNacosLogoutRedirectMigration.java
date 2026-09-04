package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Adds the Nacos console root as a registered redirect URI on the
 * {@code nacos} client, so the IdP's {@code /oidc/logout} accepts
 * {@code post_logout_redirect_uri=http://localhost:18480/} sent by Nacos's
 * OIDC plugin when it performs RP-initiated logout (the whitelist requires an
 * exact match against a registered redirect URI — open-redirect protection).
 *
 * <p>
 * Merge-style and idempotent like the 2.0.38 dsh-pc migration: distinct URIs
 * are unioned, and the statement is a no-op once applied.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_41OidcNacosLogoutRedirectMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_41OidcNacosLogoutRedirectMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.41-oidc-nacos-logout-redirect";
    }

    @Override
    public String description() {
        return "nacos client: add the Nacos console root redirect URIs for logout return";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.41-oidc-nacos-logout-redirect.sql");
    }
}
