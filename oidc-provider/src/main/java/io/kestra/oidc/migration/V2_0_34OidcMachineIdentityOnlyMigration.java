package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Machine identities are identity-only: no machine is an administrator.
 *
 * <p>
 * 2.0.33 converged {@code kestra-self} to the identity-only {@code authenticated} role but
 * kept {@code dsh} and {@code nacos} on {@code ["admin"]}, arguing their consumers contract
 * on it. That design was rejected: a machine that only needs to be authenticated by this IdP
 * must not appear as an administrator in the directory. This migration completes the
 * convergence — every remaining machine with the {@code admin} role is flattened to
 * {@code ["authenticated"]}.
 *
 * <p>
 * The two consumers' needs are now served without any machine holding {@code admin} in the
 * directory:
 * <ul>
 *   <li>{@code dsh} — full observation-centre access derives from being a service identity
 *       ({@code sub == client_id}); the dsh APIs owner-scope only human non-admin callers
 *       (DshApprovalController / DshMetricsController use {@code isService() || isAdmin()});</li>
 *   <li>{@code nacos} — the Nacos OIDC plugin derives its admin from the token roles claim
 *       ({@code OIDC_ADMIN_ROLE=admin}); that claim is injected at token-issue time by
 *       {@code OidcConfiguration.clientTokenRolesOverride}, so the nacos directory row stays
 *       identity-only while its {@code client_credentials} token carries
 *       {@code ["authenticated","admin"]} for the Nacos plugin to match.</li>
 * </ul>
 *
 * <p>
 * New machine identities already default to {@code AUTHENTICATED_ROLE} at creation
 * ({@code OidcUserService}), so this is a one-time data fix for the pre-existing seed.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_34OidcMachineIdentityOnlyMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_34OidcMachineIdentityOnlyMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.34-oidc-machine-identity-only";
    }

    @Override
    public String description() {
        return "Machine identities are identity-only: no machine keeps the admin role (dsh/nacos converged from admin)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.34-oidc-machine-identity-only.sql");
    }
}
