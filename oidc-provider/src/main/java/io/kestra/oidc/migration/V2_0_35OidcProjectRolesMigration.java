package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Project + project-level roles + role assignments (ZITADEL-aligned).
 *
 * <p>
 * Introduces the Project abstraction: a project contains Applications
 * ({@code oidc_client}) and project-scoped Roles. A user's roles are bound
 * via Role Assignment (user + project + role), and the token's roles claim is
 * issued from the assignments in the project that the requesting client belongs to.
 *
 * <p>
 * This replaces the global {@code oidc_user.roles} field with project-scoped
 * assignments, and retires the {@code clientTokenRolesOverride} hack (nacos
 * admin is now a regular project role assignment on the nacos machine identity).
 *
 * <p>
 * Default project: {@code "dsh"} — contains all existing applications
 * (kestra-self, nacos, dsh, dsh-ui, dsh-pc) and three built-in roles:
 * <ul>
 *   <li>{@code admin} — dsh ecosystem admin (Kestra user/role mgmt + nacos
 *       global admin + dsh data full access)</li>
 *   <li>{@code user} — dsh ecosystem regular user (Kestra normal features +
 *       dsh data scoped to self)</li>
 *   <li>{@code authenticated} — identity-only, no authorization (machine
 *       identity default)</li>
 * </ul>
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_35OidcProjectRolesMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_35OidcProjectRolesMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.35-oidc-project-roles";
    }

    @Override
    public String description() {
        return "Project + project-level roles + role assignments (ZITADEL-aligned); retires clientTokenRolesOverride";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.35-oidc-project-roles.sql");
    }
}
