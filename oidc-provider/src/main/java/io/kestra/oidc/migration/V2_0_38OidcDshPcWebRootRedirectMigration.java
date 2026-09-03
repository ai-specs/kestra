package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Adds the dsh web deployment root as a registered redirect URI on the
 * {@code dsh-pc} public client, so the IdP's {@code /oidc/logout} accepts
 * {@code post_logout_redirect_uri=http://localhost:13000/} from dsh web's
 * logout route (the whitelist requires an exact match against a registered
 * redirect URI — open-redirect protection).
 *
 * <p>
 * Merge-style and idempotent like the callback migration (2.0.30): distinct
 * URIs are unioned, and the statement is a no-op once applied.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_38OidcDshPcWebRootRedirectMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_38OidcDshPcWebRootRedirectMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.38-oidc-dsh-pc-web-root-redirect";
    }

    @Override
    public String description() {
        return "dsh-pc client: add the deployment root redirect URIs for logout return";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.38-oidc-dsh-pc-web-root-redirect.sql");
    }
}
