package io.kestra.oidc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import io.kestra.oidc.services.OidcUserService;
import io.kestra.oidc.services.OidcUserService.CreateUserRequest;
import io.kestra.oidc.services.OidcUserService.UpdateUserRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the user-directory administration surface (list / create / get / update / delete /
 * roles / password) consumed by the Kestra UI 用户控制 & 权限控制 pages.
 */
class OidcUserAdminServiceTest extends OidcPostgresTestBase {

    @BeforeEach
    void resetDirectory() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM oidc_user_auth_method");
            s.executeUpdate("DELETE FROM oidc_user");
            s.executeUpdate("DELETE FROM oidc_client");
        }
        userService.bootstrap();
    }

    @Test
    void createUserPersistsProfileAndPassword() {
        OidcUserService.UserRow row = userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        assertEquals("bob@example.com", row.username());
        assertEquals("Bob", row.name());
        assertEquals(List.of("user"), row.roles());
        assertEquals("ACTIVE", row.userState());
        assertNotNull(row.createdAt());

        // the bcrypt password must be accepted by the login path
        assertTrue(userService.validateCredentials("bob@example.com", "BobPass123!"));
        assertFalse(userService.validateCredentials("bob@example.com", "nope"));
        assertEquals(List.of("user"), userService.bySubject("bob@example.com").roles());
    }

    @Test
    void createUserWithoutPasswordStillPersists() {
        OidcUserService.UserRow row = userService.createUser(new CreateUserRequest(
            "carol@example.com", "Carol", "carol@example.com", null, "ACTIVE", List.of("user"), "human", null, null));
        assertEquals("carol@example.com", row.username());
        assertFalse(userService.validateCredentials("carol@example.com", "anything"));
    }

    @Test
    void createUserDefaultsRolesToConfiguredDefault() {
        OidcUserService.UserRow row = userService.createUser(new CreateUserRequest(
            "dave@example.com", "Dave", "dave@example.com", "DavePass123!", "ACTIVE", null, "human", null, null));
        // the base sets default roles to [admin]
        assertEquals(List.of("admin"), row.roles());
    }

    @Test
    void createUserDuplicateIsRejected() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));
        assertThrows(Exception.class, () -> userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob2", "bob@example.com", "X", "ACTIVE", List.of("user"), "human", null, null)));
    }

    @Test
    void getUserReturnsDetailWithAuthMethods() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        var detail = userService.getUser("bob@example.com").orElseThrow();
        assertEquals("Bob", detail.name());
        assertEquals("bob@example.com", detail.email());
        assertEquals(List.of("user"), detail.roles());
        assertEquals(1, detail.authMethods().size());
        assertEquals("PASSWORD", detail.authMethods().get(0).type());
        assertEquals("ACTIVE", detail.authMethods().get(0).state());
        // credential hashes must never be exposed
        assertTrue(detail.authMethods().stream().noneMatch(a -> a.name() != null && a.name().contains("$2a$")));

        assertTrue(userService.getUser("nobody@example.com").isEmpty());
    }

    @Test
    void updateUserChangesProfileFields() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        OidcUserService.UserRow updated = userService.updateUser("bob@example.com",
            new UpdateUserRequest("Bobby", "bobby@example.com", "+8613800000000", "INACTIVE", true));

        assertEquals("Bobby", updated.name());
        assertEquals("bobby@example.com", updated.email());
        assertEquals("INACTIVE", updated.userState());

        var detail = userService.getUser("bob@example.com").orElseThrow();
        assertEquals("+8613800000000", detail.phone());
        assertTrue(detail.passwordChangeRequired());
        // inactive users can no longer log in
        assertFalse(userService.validateCredentials("bob@example.com", "BobPass123!"));
    }

    @Test
    void setRolesReplacesUserRoles() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        OidcUserService.UserRow updated = userService.setRoles("bob@example.com", List.of("admin", "operator"));
        assertEquals(List.of("admin", "operator"), updated.roles());
        assertEquals(List.of("admin", "operator"), userService.bySubject("bob@example.com").roles());
    }

    @Test
    void resetPasswordReplacesHashAndReactivates() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        assertTrue(userService.resetPassword("bob@example.com", "NewPass456!"));
        assertFalse(userService.validateCredentials("bob@example.com", "BobPass123!"));
        assertTrue(userService.validateCredentials("bob@example.com", "NewPass456!"));
    }

    @Test
    void deleteUserRemovesProfileAndAuthMethods() {
        userService.createUser(new CreateUserRequest(
            "bob@example.com", "Bob", "bob@example.com", "BobPass123!", "ACTIVE", List.of("user"), "human", null, null));

        assertTrue(userService.deleteUser("bob@example.com"));
        assertFalse(userService.deleteUser("bob@example.com"));
        assertFalse(userService.validateCredentials("bob@example.com", "BobPass123!"));
        assertTrue(userService.getUser("bob@example.com").isEmpty());
        // auth methods cascaded
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT count(*) FROM oidc_user_auth_method WHERE user_id = 'bob@example.com'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void listUsersReturnsNewestFirstAndSearches() {
        userService.createUser(new CreateUserRequest(
            "aaa@example.com", "Aaa", "aaa@example.com", "Aaapass123!", "ACTIVE", List.of("user"), "human", null, null));
        userService.createUser(new CreateUserRequest(
            "bbb@example.com", "Bbb", "bbb@example.com", "Bbbpass123!", "ACTIVE", List.of("user"), "human", null, null));

        List<OidcUserService.UserRow> all = userService.listUsers(null, null, 0, 100);
        // seeded admin + two new users, newest first
        assertEquals(3, all.size());
        assertEquals("bbb@example.com", all.get(0).username());
        assertEquals("aaa@example.com", all.get(1).username());

        List<OidcUserService.UserRow> byName = userService.listUsers("Aaa", null, 0, 100);
        assertEquals(1, byName.size());
        assertEquals("aaa@example.com", byName.get(0).username());

        List<OidcUserService.UserRow> byEmail = userService.listUsers("bbb@example.com", null, 0, 100);
        assertEquals(1, byEmail.size());
        assertEquals("bbb@example.com", byEmail.get(0).username());

        // offset/size paging
        List<OidcUserService.UserRow> page = userService.listUsers(null, null, 0, 2);
        assertEquals(2, page.size());
    }

    // ------------------------------------------------------------------ machine identities

    @Test
    void createMachinePersistsDirectoryRowAndClient() {
        OidcUserService.UserRow row = userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync Service", null, null, "ACTIVE",
            List.of("admin"), "machine", "Syncs external data hourly", "svc-secret-123"));

        assertEquals("machine", row.type());
        assertEquals("svc-data", row.username());
        assertEquals(List.of("admin"), row.roles());
        assertEquals("ACTIVE", row.userState());

        // machine child row persisted with description
        var detail = userService.getUser("svc-data").orElseThrow();
        assertEquals("machine", detail.type());
        assertEquals("Syncs external data hourly", detail.description());
        assertEquals("bearer", detail.accessTokenType());
        // machine identities have no password auth method
        assertEquals(0, detail.authMethods().size());
        assertTrue(userService.isMachineIdentity("svc-data"));
        assertTrue(userService.isActive("svc-data"));

        // the oidc_client credential record was created so client_credentials can authenticate
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT client_secret, grant_types FROM oidc_client WHERE client_id = 'svc-data'")) {
            rs.next();
            assertEquals("svc-secret-123", rs.getString(1));
            assertTrue(rs.getString(2).contains("client_credentials"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void machineIdentityRejectsPasswordReset() {
        userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync", null, null, "ACTIVE",
            List.of("admin"), "machine", null, "svc-secret-123"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> userService.resetPassword("svc-data", "Whatever123!"));
        assertTrue(e.getMessage().contains("no password"));
        assertFalse(userService.validateCredentials("svc-data", "Whatever123!"));
    }

    @Test
    void inactiveMachineBlocksClientCredentials() {
        userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync", null, null, "ACTIVE",
            List.of("admin"), "machine", null, "svc-secret-123"));
        assertTrue(userService.isActive("svc-data"));

        userService.updateUser("svc-data", new UpdateUserRequest(null, null, null, "INACTIVE", null));
        assertFalse(userService.isActive("svc-data"));
    }

    @Test
    void rotateSecretUpdatesClientCredential() {
        userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync", null, null, "ACTIVE",
            List.of("admin"), "machine", null, "svc-secret-123"));

        String rotated = userService.rotateSecret("svc-data", null);
        assertNotNull(rotated);
        assertFalse("svc-secret-123".equals(rotated));

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT client_secret FROM oidc_client WHERE client_id = 'svc-data'")) {
            rs.next();
            assertEquals(rotated, rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deleteMachineRemovesDirectoryRowAndClient() {
        userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync", null, null, "ACTIVE",
            List.of("admin"), "machine", null, "svc-secret-123"));

        assertTrue(userService.deleteUser("svc-data"));
        assertTrue(userService.getUser("svc-data").isEmpty());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT count(*) FROM oidc_client WHERE client_id = 'svc-data'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deleteMachineWithIssuedTokensRemovesClient() {
        userService.createUser(new CreateUserRequest(
            "svc-data", "Data Sync", null, null, "ACTIVE",
            List.of("admin"), "machine", null, "svc-secret-123"));
        // simulate an issued access token (oidc_token references oidc_client via a non-cascading FK)
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO oidc_token (client_id, subject, token_type, value, scopes) "
                + "VALUES ('svc-data', 'svc-data', 'access', 'tok-1', '[\"openid\"]'::jsonb)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        assertTrue(userService.deleteUser("svc-data"));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery(
                 "SELECT count(*) FROM oidc_client WHERE client_id = 'svc-data'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void listUsersFiltersByIdentityType() {
        userService.createUser(new CreateUserRequest(
            "svc-a", "Svc A", null, null, "ACTIVE",
            List.of("user"), "machine", null, "svc-a-secret"));
        userService.createUser(new CreateUserRequest(
            "zzz@example.com", "Zzz", "zzz@example.com", "Zzzpass123!", "ACTIVE",
            List.of("user"), "human", null, null));

        List<OidcUserService.UserRow> machines = userService.listUsers(null, "machine", 0, 100);
        assertEquals(1, machines.size());
        assertEquals("svc-a", machines.get(0).username());
        assertEquals("machine", machines.get(0).type());

        List<OidcUserService.UserRow> humans = userService.listUsers(null, "human", 0, 100);
        assertTrue(humans.stream().allMatch(r -> "human".equals(r.type())));
    }
}
