package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Seeds the dsh-ui (mobile) and dsh-pc (user PC) PUBLIC OIDC clients and adds session ownership
 * (owner = OIDC sub) plus the mobile pending-input columns to {@code dsh_session}.
 *
 * <p>
 * Public clients are stored with an empty secret: they cannot keep credentials safe, so they
 * authenticate exclusively with PKCE (S256) — dsh.docx 统一认证: 用户接入端一律
 * Authorization Code + PKCE(S256)，客户端不持有 client_secret.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_28OidcPublicClientsDshOwnerMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_28OidcPublicClientsDshOwnerMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.28-oidc-public-clients-dsh-owner";
    }

    @Override
    public String description() {
        return "Seed dsh-ui/dsh-pc public clients; add dsh_session owner + pending input";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.28-oidc-public-clients-dsh-owner.sql");
    }
}
