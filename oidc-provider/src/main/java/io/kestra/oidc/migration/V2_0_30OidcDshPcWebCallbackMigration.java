package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Adds the dsh web OIDC sign-in callback to the {@code dsh-pc} public client:
 * when {@code dsh web} runs with {@code auth=oidc}, the browser completes
 * Authorization Code + PKCE against the unified IdP and returns to
 * {@code http://localhost:13000/oidc/callback} (the deployment's web origin).
 *
 * <p>
 * The daemon loopback redirect registered by 2.0.28 stays — the two flows
 * serve different consumers (standalone daemon vs. the web UI's own fence) and
 * both authenticate the same {@code dsh-pc} identity. The statement merges
 * distinct URIs instead of overwriting, and is a no-op once applied.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_30OidcDshPcWebCallbackMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_30OidcDshPcWebCallbackMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.30-oidc-dsh-pc-web-callback";
    }

    @Override
    public String description() {
        return "dsh-pc client: add the dsh web OIDC callback redirect URIs";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.30-oidc-dsh-pc-web-callback.sql");
    }
}
