package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Introduces the identity-only {@code authenticated} role for machine identities.
 *
 * <p>
 * 2.0.32 migrated the confidential {@code client_credentials} clients into the directory
 * with an explicit {@code ["admin"]} to freeze the behaviour they previously got from the
 * {@code default-roles} fallback. That froze an over-grant: a machine that only needs to be
 * authenticated by the IdP carried kestra-admin with no management surface. This migration
 * converges the machines that have no elevated-rights contract to the identity-only
 * {@code authenticated} role, keeping {@code dsh} and {@code nacos} on {@code admin} because
 * their consumers contract on it:
 * <ul>
 *   <li>{@code dsh} — the Worker {@code DshStore} reads/writes the full observation centre;
 *       the dsh session/approval/metrics APIs owner-scope non-admin callers;</li>
 *   <li>{@code nacos} — the Nacos OIDC plugin maps the token {@code roles} claim
 *       ({@code OIDC_ADMIN_ROLE=admin}) to its own admin principal (app-scripts/nacos/init.sh).</li>
 * </ul>
 * New machine identities now default to {@code authenticated} at creation
 * ({@code OidcUserService.AUTHENTICATED_ROLE}), so this is a one-time data fix for the
 * pre-existing seed.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_33OidcMachineAuthenticatedRoleMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_33OidcMachineAuthenticatedRoleMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.33-oidc-machine-authenticated-role";
    }

    @Override
    public String description() {
        return "Machine identities default to the identity-only 'authenticated' role (kestra-self converged from admin)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.33-oidc-machine-authenticated-role.sql");
    }
}
