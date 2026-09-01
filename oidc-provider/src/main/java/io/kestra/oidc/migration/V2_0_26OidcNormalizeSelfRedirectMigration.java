package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Normalizes the {@code kestra-self} redirect URI to the provider's own callback path
 * {@code /oidc/callback}.
 *
 * <p>
 * The Micronaut OAuth2 client registers its routes under the configurable template
 * {@code /oauth/callback/{name}} whenever ANY native client (third-party IdP included) is
 * configured. A controller of ours on that same variable-pattern path would collide
 * (ambiguous route at startup), so the self-bootstrap callback must live under the
 * provider's own {@code /oidc/**} namespace — which also keeps it inside the
 * {@code isAnonymous()} whitelist. This migration folds every earlier seed value
 * (both {@code /oidc/callback} and the interim {@code /oauth/callback/kestra-oidc})
 * into the canonical {@code /oidc/callback}, freeing {@code /oauth/**} entirely for the
 * framework's native client.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_26OidcNormalizeSelfRedirectMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_26OidcNormalizeSelfRedirectMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.26-oidc-normalize-self-redirect";
    }

    @Override
    public String description() {
        return "Normalize kestra-self redirect to /oidc/callback (free /oauth/** for the native Micronaut client)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.26-oidc-normalize-self-redirect.sql");
    }
}
