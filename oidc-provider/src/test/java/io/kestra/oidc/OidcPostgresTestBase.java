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

        // Apply ALL production migrations in filename order (2.0.25 … latest). Enumerating the
        // classpath directory keeps this base in sync automatically: services evolve with the
        // schema (e.g. OidcUserService reads oidc_role_assignment from 2.0.35), so any migration
        // added later is picked up without touching this file again.
        try {
            var migrationsUrl = Thread.currentThread().getContextClassLoader().getResource("migrations");
            if (migrationsUrl == null || !"file".equals(migrationsUrl.getProtocol())) {
                throw new IllegalStateException("migrations directory not found on classpath: " + migrationsUrl);
            }
            java.util.List<String> scripts;
            try (var files = java.nio.file.Files.list(java.nio.file.Path.of(migrationsUrl.toURI()))) {
                scripts = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
            }
            for (String name : scripts) {
                AbstractSQLMigrationScript.executeSqlScript(dataSource, "/migrations/" + name);
            }
        } catch (java.io.IOException | java.sql.SQLException | java.net.URISyntaxException e) {
            throw new IllegalStateException("failed to apply migrations", e);
        }

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
