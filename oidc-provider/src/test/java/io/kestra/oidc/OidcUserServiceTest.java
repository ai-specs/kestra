package io.kestra.oidc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import io.kestra.oidc.OidcConfiguration.OidcUserAccount;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the persistent OIDC user directory: bootstrap seeding, bcrypt-backed credential
 * validation, user state enforcement and stored roles. Each test starts from a fresh,
 * re-seeded directory.
 */
class OidcUserServiceTest extends OidcPostgresTestBase {

    @BeforeEach
    void resetDirectory() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM oidc_user_auth_method");
            s.executeUpdate("DELETE FROM oidc_user");
        }
        userService.bootstrap();
    }

    @Test
    void bootstrapSeedsConfiguredAdmin() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT username, roles, user_state FROM oidc_user")) {
            assertTrue(rs.next(), "expected the admin account to be seeded");
            assertEquals("admin@kestra.io", rs.getString("username"));
            assertEquals("ACTIVE", rs.getString("user_state"));
            assertTrue(rs.getString("roles").contains("\"admin\""), "admin should carry the admin role");
            assertFalse(rs.next(), "no other users should be seeded by default");
        }
        // password method must carry a bcrypt hash for the admin
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT type, credential FROM oidc_user_auth_method WHERE user_id = 'admin@kestra.io'")) {
            assertTrue(rs.next());
            assertEquals("PASSWORD", rs.getString("type"));
            assertNotNull(rs.getString("credential"));
            assertTrue(BCrypt.checkpw("Admin1234!", rs.getString("credential")));
        }
    }

    @Test
    void correctPasswordIsAccepted() {
        assertTrue(userService.validateCredentials("admin@kestra.io", "Admin1234!"));
    }

    @Test
    void wrongPasswordIsRejected() {
        assertFalse(userService.validateCredentials("admin@kestra.io", "wrong-password"));
        assertFalse(userService.validateCredentials("admin@kestra.io", null));
        assertFalse(userService.validateCredentials("nobody@kestra.io", "Admin1234!"));
    }

    @Test
    void inactiveUserIsRejected() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE oidc_user SET user_state = 'INACTIVE' WHERE username = 'admin@kestra.io'");
        }
        assertFalse(userService.validateCredentials("admin@kestra.io", "Admin1234!"));
    }

    @Test
    void bySubjectReturnsStoredRoles() {
        var user = userService.bySubject("admin@kestra.io");
        assertEquals("admin@kestra.io", user.sub());
        assertEquals(List.of("admin"), user.roles());
    }

    @Test
    void bySubjectUnknownFallsBackToDefaultRoles() {
        var user = userService.bySubject("some-service-account");
        assertEquals(List.of("admin"), user.roles());
    }

    @Test
    void bootstrapSeedsConfiguredUsers() {
        OidcUserAccount alice = new OidcUserAccount();
        alice.setUsername("alice@kestra.io");
        alice.setPassword("Alice1234!");
        alice.setRoles(List.of("user"));
        configuration.setUsers(List.of(alice));

        // wipe and re-seed so alice is picked up
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM oidc_user_auth_method");
            s.executeUpdate("DELETE FROM oidc_user");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        userService.bootstrap();

        assertTrue(userService.validateCredentials("alice@kestra.io", "Alice1234!"));
        assertEquals(List.of("user"), userService.bySubject("alice@kestra.io").roles());
        configuration.setUsers(List.of());
    }
}
