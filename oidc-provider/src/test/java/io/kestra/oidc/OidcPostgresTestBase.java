package io.kestra.oidc;

import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;
import io.kestra.oidc.services.OidcAuthorizationCodeService;
import io.kestra.oidc.services.OidcClientService;
import io.kestra.oidc.services.OidcJwkService;
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

        // Apply the real migration (creates the 4 tables + seeds default clients + default RSA JWK).
        AbstractSQLMigrationScript.executeSqlScript(dataSource, "/migrations/2.0.25-oidc-provider-postgres.sql");

        objectMapper = new ObjectMapper();
        configuration = new OidcConfiguration();
        configuration.setIssuer("http://localhost:18080");
        configuration.setDefaultRoles(java.util.List.of("admin"));

        jwkService = new OidcJwkService(dataSource);
        clientService = new OidcClientService(dataSource, objectMapper);
        authCodeService = new OidcAuthorizationCodeService(dataSource, objectMapper, configuration);
        tokenService = new OidcTokenService(dataSource, objectMapper, configuration, jwkService);
        userService = new OidcUserService(Optional.empty(), configuration);
    }

    @AfterAll
    static void destroy() {
        if (POSTGRES != null) {
            POSTGRES.stop();
        }
    }
}
