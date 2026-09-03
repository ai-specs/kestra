package io.kestra.oidc;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;
import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcClientService;
import io.kestra.oidc.services.OidcJwkService;
import io.kestra.oidc.services.OidcSessionService;
import io.kestra.oidc.services.OidcTokenService;
import io.kestra.oidc.services.OidcUserService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for OIDC Provider tests: boots a real PostgreSQL (Testcontainers), applies the exact
 * production migration ({@code 2.0.25-oidc-provider-postgres.sql}) and instantiates the services
 * against it.
 */
@Testcontainers
public abstract class OidcPostgresTestBase {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    protected static DataSource dataSource;
    protected static ObjectMapper objectMapper;
    protected static OidcConfiguration configuration;
    protected static OidcClientService clientService;
    protected static OidcJwkService jwkService;
    protected static OidcAuthorizationCodeService authCodeService;
    protected static OidcTokenService tokenService;
    protected static OidcUserService userService;

    @BeforeAll
    static void init() throws Exception {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        // Apply the real migrations (creates the OIDC tables + seeds default clients + default RSA
        // JWK, the 2.0.31 user directory tables oidc_user / oidc_user_auth_method, and the 2.0.32
        // machine-identity split oidc_user.type / oidc_user_machine).
        AbstractSQLMigrationScript.executeSqlScript(dataSource, "/migrations/2.0.25-oidc-provider-postgres.sql");
        AbstractSQLMigrationScript.executeSqlScript(dataSource, "/migrations/2.0.31-oidc-user-schema.sql");
        AbstractSQLMigrationScript.executeSqlScript(dataSource, "/migrations/2.0.32-oidc-machine-identities.sql");

        objectMapper = new ObjectMapper();
        configuration = new OidcConfiguration();
        configuration.setIssuer("http://localhost:18080");
        configuration.setDefaultRoles(java.util.List.of("admin"));

        jwkService = new OidcJwkService(dataSource);
        clientService = new OidcClientService(dataSource, objectMapper);
        authCodeService = new OidcAuthorizationCodeService(dataSource, objectMapper, configuration);
        tokenService = new OidcTokenService(dataSource, objectMapper, configuration, jwkService);
        userService = new OidcUserService(
            new OidcSessionService(configuration), configuration, clientService, dataSource, objectMapper);
        // Manual bootstrap: @PostConstruct only fires inside a Micronaut container.
        userService.bootstrap();
    }

    @AfterAll
    static void destroy() {
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
    }
}
