package io.kestra.oidc.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nimbusds.oauth2.sdk.OAuth2Error;

import io.kestra.oidc.OidcConfiguration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.http.HttpRequest;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the OIDC user from the provider's own login session and directory.
 *
 * <p>
 * This provider deliberately does <b>not</b> use Kestra's Basic Auth at all (Basic Auth re-sends
 * {@code username:password} with every request, which is why it is banned here). Instead:
 * <ul>
 *   <li>{@code POST /oidc/login} validates the credentials against the persistent user directory
 *       ({@code oidc_user} + {@code oidc_user_auth_method}, see the 2.0.31 migration) and creates
 *       a server-side {@link OidcSessionService session};</li>
 *   <li>{@link #authenticatedUser(HttpRequest)} resolves the user from the {@code oidc_session}
 *       cookie only.</li>
 * </ul>
 * The username (an email) becomes the {@code sub}/{@code name}/{@code email} claims, and the
 * user's stored roles become the {@code roles} claim.
 *
 * <p>
 * Bootstrap: when the directory is empty the configured accounts
 * ({@code kestra.oidc.admin-username}/{@code admin-password} plus {@code kestra.oidc.users}) are
 * seeded with bcrypt-hashed passwords, so a fresh deployment (even with a bare default config)
 * starts with the documented accounts. When the tables are absent (e.g. a non-Postgres
 * standalone repository) the service falls back to the legacy config-only path so standalone
 * keeps working.
 */
@Singleton
@Requires(property = "kestra.oidc.enabled", notEquals = "false")
public class OidcUserService {

    /** An OIDC user resolved from the provider's login session. */
    public record OidcUser(String sub, String name, String email, List<String> roles) {}

    /** Identity type discriminator (ZITADEL users14.type). */
    public static final String TYPE_HUMAN = "human";
    public static final String TYPE_MACHINE = "machine";

    /**
     * The built-in "identity-only" role. An identity holding only this role has been
     * authenticated by the IdP but carries no authorisation (mirrors ZITADEL's implicit
     * {@code authenticated} role, made explicit here so it is visible and manageable in the
     * directory). Machine identities default to it instead of falling back to
     * {@code default-roles} ({@code admin}) so a new service account is least-privilege by
     * default; a consumer that really needs elevated rights (e.g. the dsh service reading the
     * full observation centre, or Nacos mapping the {@code roles} claim to its own admin) is
     * granted {@code admin} explicitly.
     */
    public static final String AUTHENTICATED_ROLE = "authenticated";

    /** A user-directory row exposed by the admin API (no auth-method detail). */
    public record UserRow(
        String username,
        String name,
        String email,
        String userState,
        List<String> roles,
        String type,
        Instant createdAt,
        Instant lastLoginAt
    ) {}

    /** An auth-method row of a user (credential hash is never exposed). */
    public record AuthMethodRow(Long id, String type, String state, String name, Instant createdAt) {}

    /** Full user profile as consumed by the admin API detail endpoint. */
    public record UserDetail(
        String username,
        String name,
        String firstName,
        String lastName,
        String email,
        boolean emailVerified,
        String phone,
        boolean phoneVerified,
        String preferredLanguage,
        String userState,
        boolean passwordChangeRequired,
        List<String> roles,
        String type,
        String description,
        String accessTokenType,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<AuthMethodRow> authMethods
    ) {}

    /** Payload for creating a user through the admin API. */
    public record CreateUserRequest(
        String username,
        String name,
        String email,
        String password,
        String userState,
        List<String> roles,
        String type,
        String description,
        String secret
    ) {}

    /** Payload for updating a user profile through the admin API. */
    public record UpdateUserRequest(
        String name,
        String email,
        String phone,
        String userState,
        Boolean passwordChangeRequired
    ) {}

    private static final Logger log = LoggerFactory.getLogger(OidcUserService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final OidcSessionService sessionService;
    private final OidcConfiguration configuration;
    private final OidcClientService clientService;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    private volatile boolean tableProbeDone = false;
    private volatile boolean tableAvailable = false;
    private volatile boolean seeded = false;
    private volatile boolean warnedMissingTable = false;

    @Inject
    public OidcUserService(
        OidcSessionService sessionService,
        OidcConfiguration configuration,
        OidcClientService clientService,
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.configuration = configuration;
        this.clientService = clientService;
        // Unwrap any Micronaut Data AOP proxy so getConnection() works outside a @Connectable context.
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.objectMapper = objectMapper;
    }

    /**
     * Seeds the configured accounts into the directory when it is empty. Invoked by Micronaut at
     * startup ({@code @PostConstruct}) and lazily on every credential/subject lookup, so a bean
     * created before the 2.0.31 migration runs still seeds once the tables exist. Tests call it
     * directly after wiping the directory.
     */
    public void bootstrap() {
        seeded = false;
        ensureDirectory();
    }

    /**
     * Makes sure the directory exists and is seeded before a lookup. Cheap after the first
     * successful probe; a failed probe is not cached (the migration may not have run yet), so
     * the next call retries.
     */
    private void ensureDirectory() {
        if (!tableAvailable()) {
            return;
        }
        if (seeded) {
            return;
        }
        if (countUsers() <= 0) {
            seedConfiguredAccounts();
        }
        seeded = true;
    }

    /**
     * Validates a username/password against the user directory (bcrypt) — or against the
     * configured accounts when the tables are unavailable. Used by the IdP login form —
     * credentials are submitted once and exchanged for a session cookie, never re-sent.
     */
    public boolean validateCredentials(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        ensureDirectory();
        if (!tableAvailable()) {
            return validateFromConfig(username, password);
        }
        Optional<StoredUser> user = findUser(username);
        if (user.isEmpty() || !"ACTIVE".equals(user.get().userState())) {
            return false;
        }
        Optional<String> hash = findPasswordHash(username);
        if (hash.isEmpty() || !BCrypt.checkpw(password, hash.get())) {
            return false;
        }
        updateLastLogin(username);
        return true;
    }

    /**
     * Returns the authenticated user for the request, resolved from the {@code oidc_session}
     * cookie — empty when the browser has not logged into the provider.
     */
    public Optional<OidcUser> authenticatedUser(HttpRequest<?> request) {
        Optional<String> sessionId = sessionService.sessionIdFrom(request);
        if (sessionId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> subject = sessionService.subject(sessionId.get());
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bySubject(subject.get()));
    }

    /** Same as {@link #authenticatedUser(HttpRequest)} but throws {@code access_denied} when absent. */
    public OidcUser requireAuthenticatedUser(HttpRequest<?> request) {
        return authenticatedUser(request)
            .orElseThrow(() -> new OidcException(OAuth2Error.ACCESS_DENIED.appendDescription(": user is not authenticated")));
    }

    /**
     * Rebuilds a user profile from a known subject (e.g. the subject stored in an authorization
     * code or refresh token). Resolves the user from the directory when available; unknown
     * subjects (e.g. a client-credentials client id) and the config fallback map to the
     * configured default roles.
     */
    public OidcUser bySubject(String subject) {
        ensureDirectory();
        if (tableAvailable()) {
            Optional<StoredUser> user = findUser(subject);
            if (user.isPresent()) {
                return new OidcUser(subject, user.get().name(), user.get().email(), user.get().roles());
            }
            return new OidcUser(subject, subject, subject, configuration.getDefaultRoles());
        }
        return bySubjectFromConfig(subject);
    }

    // ------------------------------------------------------------------
    // Admin / management API (user-directory CRUD for the Kestra UI)
    // ------------------------------------------------------------------

    /**
     * Lists users from the directory, newest first, with an optional free-text search over
     * username/name/email and an optional identity-type filter ({@code human} / {@code machine}).
     * Admin surface only — callers must be checked by the controller.
     */
    public List<UserRow> listUsers(String search, String type, int offset, int size) {
        ensureDirectory();
        if (!tableAvailable()) {
            return List.of();
        }
        int safeSize = Math.max(1, Math.min(size, 500));
        int safeOffset = Math.max(0, offset);
        String typeFilter = type == null || type.isBlank() ? null : type.trim().toLowerCase();
        List<UserRow> rows = new ArrayList<>();
        final String sql = """
            SELECT username, name, email, user_state, roles, type, created_at, last_login_at
            FROM oidc_user
            WHERE (? IS NULL OR username ILIKE ? OR name ILIKE ? OR email ILIKE ?)
              AND (? IS NULL OR type = ?)
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            String pattern = search == null || search.isBlank() ? null : "%" + search.trim() + "%";
            ps.setObject(1, pattern, Types.VARCHAR);
            ps.setObject(2, pattern, Types.VARCHAR);
            ps.setObject(3, pattern, Types.VARCHAR);
            ps.setObject(4, pattern, Types.VARCHAR);
            ps.setObject(5, typeFilter, Types.VARCHAR);
            ps.setObject(6, typeFilter, Types.VARCHAR);
            ps.setInt(7, safeSize);
            ps.setInt(8, safeOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapUserRow(rs));
                }
            }
        } catch (SQLException e) {
            if (isMissingRelation(e)) {
                tableAvailable = false;
            }
            log.warn("oidc_user list failed: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("oidc_user list row parse failed: {}", e.getMessage());
        }
        return rows;
    }

    /** Full profile of one user, including its auth methods (without credential hashes). */
    public Optional<UserDetail> getUser(String username) {
        ensureDirectory();
        if (!tableAvailable() || username == null) {
            return Optional.empty();
        }
        final String sql = """
            SELECT u.username, u.name, u.first_name, u.last_name, u.email, u.email_verified,
                   u.phone, u.phone_verified, u.preferred_language, u.user_state,
                   u.password_change_required, u.roles, u.type, u.created_at, u.updated_at,
                   u.last_login_at, m.description, m.access_token_type
            FROM oidc_user u
            LEFT JOIN oidc_user_machine m ON m.user_id = u.username
            WHERE u.username = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UserDetail detail = new UserDetail(
                    rs.getString("username"),
                    rs.getString("name"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getString("email"),
                    rs.getBoolean("email_verified"),
                    rs.getString("phone"),
                    rs.getBoolean("phone_verified"),
                    rs.getString("preferred_language"),
                    rs.getString("user_state"),
                    rs.getBoolean("password_change_required"),
                    objectMapper.readValue(rs.getString("roles"), STRING_LIST),
                    rs.getString("type"),
                    rs.getString("description"),
                    rs.getString("access_token_type"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    toInstant(rs.getTimestamp("last_login_at")),
                    listAuthMethods(connection, username)
                );
                return Optional.of(detail);
            }
        } catch (SQLException e) {
            if (isMissingRelation(e)) {
                tableAvailable = false;
            }
            log.warn("oidc_user get failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("oidc_user parse failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Creates an identity in the directory. A {@code human} identity gets an optional bcrypt
     * PASSWORD auth method; a {@code machine} (service account) identity gets a machine child
     * row ({@code oidc_user_machine}) plus an {@code oidc_client} credential record so it can
     * authenticate with {@code client_credentials} (a random secret is generated when none is
     * supplied).
     */
    public UserRow createUser(CreateUserRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        ensureDirectory();
        if (!tableAvailable()) {
            throw new IllegalStateException("user directory tables are unavailable");
        }
        String type = request.type() == null || request.type().isBlank()
            ? TYPE_HUMAN : request.type().trim().toLowerCase();
        if (!TYPE_HUMAN.equals(type) && !TYPE_MACHINE.equals(type)) {
            throw new IllegalArgumentException("type must be 'human' or 'machine'");
        }
        String username = request.username().trim();
        String name = request.name() == null || request.name().isBlank() ? username : request.name().trim();
        String email = request.email() == null || request.email().isBlank()
            ? (TYPE_MACHINE.equals(type) ? username + "@machine.local" : null)
            : request.email().trim();
        if (email == null) {
            throw new IllegalArgumentException("email is required for human identities");
        }
        String userState = request.userState() == null || request.userState().isBlank()
            ? "ACTIVE" : request.userState().trim().toUpperCase();
        // Least-privilege by default: a machine (service account) that only needs to be
        // authenticated gets the identity-only "authenticated" role, never an implicit admin
        // from default-roles. A human still falls back to the configured default roles.
        List<String> roles;
        if (TYPE_MACHINE.equals(type)) {
            roles = request.roles() == null || request.roles().isEmpty()
                ? new ArrayList<>(List.of(AUTHENTICATED_ROLE)) : new ArrayList<>(request.roles());
        } else {
            roles = request.roles() == null || request.roles().isEmpty()
                ? configuration.getDefaultRoles() : new ArrayList<>(request.roles());
        }
        String hash = request.password() == null || request.password().isBlank()
            ? null : BCrypt.hashpw(request.password(), BCrypt.gensalt(10));

        final String insertUser = """
            INSERT INTO oidc_user (username, name, email, user_state, roles, type)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)""";
        final String insertMethod = """
            INSERT INTO oidc_user_auth_method (user_id, type, credential)
            VALUES (?, 'PASSWORD', ?)""";
        final String insertMachine = """
            INSERT INTO oidc_user_machine (user_id, name, description, access_token_type)
            VALUES (?, ?, ?, 'bearer')""";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(insertUser)) {
                ps.setString(1, username);
                ps.setString(2, name);
                ps.setString(3, email);
                ps.setString(4, userState);
                ps.setString(5, objectMapper.writeValueAsString(roles));
                ps.setString(6, type);
                ps.executeUpdate();
            }
            if (TYPE_MACHINE.equals(type)) {
                String description = request.description() == null ? "" : request.description().trim();
                try (PreparedStatement ps = connection.prepareStatement(insertMachine)) {
                    ps.setString(1, username);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.executeUpdate();
                }
            } else if (hash != null) {
                try (PreparedStatement ps = connection.prepareStatement(insertMethod)) {
                    ps.setString(1, username);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        } catch (Exception e) {
            log.warn("oidc_user create failed for '{}': {}", username, e.getMessage());
            throw new IllegalStateException("failed to create user '" + username + "': " + e.getMessage(), e);
        }
        if (TYPE_MACHINE.equals(type)) {
            String secret = request.secret() == null || request.secret().isBlank()
                ? generateSecret() : request.secret().trim();
            clientService.create(username, secret, List.of(), List.of("client_credentials"), List.of("openid", "profile", "email"));
        }
        return findUserRow(username).orElseThrow();
    }

    /** Updates the profile fields of an existing user; null fields are left unchanged. */
    public UserRow updateUser(String username, UpdateUserRequest request) {
        if (username == null || request == null) {
            throw new IllegalArgumentException("username and request are required");
        }
        ensureDirectory();
        if (!tableAvailable()) {
            throw new IllegalStateException("user directory tables are unavailable");
        }
        final String sql = """
            UPDATE oidc_user SET
                name = COALESCE(?, name),
                email = COALESCE(?, email),
                phone = COALESCE(?, phone),
                user_state = COALESCE(?, user_state),
                password_change_required = COALESCE(?, password_change_required),
                updated_at = now()
            WHERE username = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, request.name(), Types.VARCHAR);
            ps.setObject(2, request.email(), Types.VARCHAR);
            ps.setObject(3, request.phone(), Types.VARCHAR);
            ps.setObject(4, request.userState(), Types.VARCHAR);
            if (request.passwordChangeRequired() == null) {
                ps.setNull(5, Types.BOOLEAN);
            } else {
                ps.setBoolean(5, request.passwordChangeRequired());
            }
            ps.setString(6, username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("user '" + username + "' does not exist");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("oidc_user update failed for '{}': {}", username, e.getMessage());
            throw new IllegalStateException("failed to update user '" + username + "': " + e.getMessage(), e);
        }
        return findUserRow(username).orElseThrow();
    }

    /** Removes a user; auth methods and the machine child row cascade. A machine identity also
     * has its {@code oidc_client} credential record removed. */
    public boolean deleteUser(String username) {
        if (username == null) {
            return false;
        }
        ensureDirectory();
        if (!tableAvailable()) {
            throw new IllegalStateException("user directory tables are unavailable");
        }
        boolean isMachine = isMachineIdentity(username);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                 "DELETE FROM oidc_user WHERE username = ?")) {
            ps.setString(1, username);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted && isMachine) {
                clientService.delete(username);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("oidc_user delete failed for '{}': {}", username, e.getMessage());
            throw new IllegalStateException("failed to delete user '" + username + "': " + e.getMessage(), e);
        }
    }

    /** Replaces the roles of an existing user (authorisation management). */
    public UserRow setRoles(String username, List<String> roles) {
        if (username == null || roles == null) {
            throw new IllegalArgumentException("username and roles are required");
        }
        ensureDirectory();
        if (!tableAvailable()) {
            throw new IllegalStateException("user directory tables are unavailable");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                 "UPDATE oidc_user SET roles = ?::jsonb, updated_at = now() WHERE username = ?")) {
            ps.setString(1, objectMapper.writeValueAsString(roles));
            ps.setString(2, username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("user '" + username + "' does not exist");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("oidc_user roles update failed for '{}': {}", username, e.getMessage());
            throw new IllegalStateException("failed to update roles for '" + username + "': " + e.getMessage(), e);
        }
        return findUserRow(username).orElseThrow();
    }

    /** Sets (or replaces) the PASSWORD auth method of a user with a fresh bcrypt hash.
     *  Machine identities authenticate with a client secret instead — callers must reject
     *  password resets for machines before invoking this. */
    public boolean resetPassword(String username, String password) {
        if (username == null || password == null || password.isBlank()) {
            throw new IllegalArgumentException("username and password are required");
        }
        ensureDirectory();
        if (!tableAvailable()) {
            throw new IllegalStateException("user directory tables are unavailable");
        }
        if (isMachineIdentity(username)) {
            throw new IllegalArgumentException(
                "machine identity '" + username + "' has no password; rotate the client secret instead");
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        final String sql = """
            INSERT INTO oidc_user_auth_method (user_id, type, credential, state)
            VALUES (?, 'PASSWORD', ?, 'ACTIVE')
            ON CONFLICT (user_id, type) DO UPDATE SET
                credential = EXCLUDED.credential,
                state = 'ACTIVE',
                updated_at = now()""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("oidc_user password reset failed for '{}': {}", username, e.getMessage());
            throw new IllegalStateException("failed to reset password for '" + username + "': " + e.getMessage(), e);
        }
    }

    /** Whether the directory row is a machine (service account) identity. */
    public boolean isMachineIdentity(String username) {
        if (username == null) {
            return false;
        }
        ensureDirectory();
        if (!tableAvailable()) {
            return false;
        }
        final String sql = "SELECT type FROM oidc_user WHERE username = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && TYPE_MACHINE.equals(rs.getString("type"));
            }
        } catch (SQLException e) {
            log.warn("oidc_user type lookup failed for '{}': {}", username, e.getMessage());
            return false;
        }
    }

    /** Whether the directory row exists and is ACTIVE. Used to gate client_credentials. */
    public boolean isActive(String username) {
        if (username == null) {
            return false;
        }
        ensureDirectory();
        if (!tableAvailable()) {
            // Tables unavailable (non-Postgres / migration not run): fall back to config mode
            // where service principals are implicitly active (legacy standalone behaviour).
            return true;
        }
        final String sql = "SELECT user_state FROM oidc_user WHERE username = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "ACTIVE".equals(rs.getString("user_state"));
            }
        } catch (SQLException e) {
            log.warn("oidc_user state lookup failed for '{}': {}", username, e.getMessage());
            return true;
        }
    }

    /** Rotates the client secret of a machine identity (its {@code oidc_client} record). */
    public String rotateSecret(String username, String newSecret) {
        if (username == null || !isMachineIdentity(username)) {
            throw new IllegalArgumentException("machine identity '" + username + "' does not exist");
        }
        String secret = newSecret == null || newSecret.isBlank() ? generateSecret() : newSecret.trim();
        clientService.updateSecret(username, secret);
        return secret;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private List<AuthMethodRow> listAuthMethods(Connection connection, String username) throws SQLException {
        List<AuthMethodRow> methods = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id, type, state, name, created_at FROM oidc_user_auth_method WHERE user_id = ? ORDER BY id")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    methods.add(new AuthMethodRow(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("state"),
                        rs.getString("name"),
                        toInstant(rs.getTimestamp("created_at"))
                    ));
                }
            }
        }
        return methods;
    }

    private Optional<UserRow> findUserRow(String username) {
        final String sql = """
            SELECT username, name, email, user_state, roles, type, created_at, last_login_at
            FROM oidc_user WHERE username = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUserRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("oidc_user row lookup failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("oidc_user row parse failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    private UserRow mapUserRow(ResultSet rs) throws Exception {
        return new UserRow(
            rs.getString("username"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("user_state"),
            objectMapper.readValue(rs.getString("roles"), STRING_LIST),
            rs.getString("type"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("last_login_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ------------------------------------------------------------------
    // DB access
    // ------------------------------------------------------------------

    private record StoredUser(String name, String email, String userState, List<String> roles) {}

    private boolean tableAvailable() {
        if (tableProbeDone) {
            return tableAvailable;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1 FROM oidc_user LIMIT 1").close();
            tableProbeDone = true;
            tableAvailable = true;
        } catch (SQLException e) {
            // Not cached: the 2.0.31 migration may run after this bean is created, so retry on
            // the next lookup instead of locking in the "table missing" state forever.
            tableAvailable = false;
            if (!warnedMissingTable) {
                warnedMissingTable = true;
                log.warn("oidc_user tables unavailable yet (migration not run / non-Postgres repository?); "
                    + "OIDC user directory falls back to config-only mode for now");
            }
        }
        return tableAvailable;
    }

    private Optional<StoredUser> findUser(String username) {
        final String sql = """
            SELECT name, email, user_state, roles
            FROM oidc_user WHERE username = ?""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredUser(
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("user_state"),
                    objectMapper.readValue(rs.getString("roles"), STRING_LIST)
                ));
            }
        } catch (SQLException e) {
            if (isMissingRelation(e)) {
                tableAvailable = false;
            }
            log.warn("oidc_user lookup failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("oidc_user parse failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> findPasswordHash(String username) {
        final String sql = """
            SELECT credential FROM oidc_user_auth_method
            WHERE user_id = ? AND type = 'PASSWORD' AND state = 'ACTIVE'
            ORDER BY id LIMIT 1""";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("credential")) : Optional.empty();
            }
        } catch (SQLException e) {
            if (isMissingRelation(e)) {
                tableAvailable = false;
            }
            log.warn("password lookup failed for '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    private void updateLastLogin(String username) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                 "UPDATE oidc_user SET last_login_at = now() WHERE username = ?")) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("failed to update last_login_at for '{}': {}", username, e.getMessage());
        }
    }

    private int countUsers() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM oidc_user")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            if (isMissingRelation(e)) {
                tableAvailable = false;
            }
            log.warn("oidc_user count failed: {}", e.getMessage());
            return -1;
        }
    }

    private void seedConfiguredAccounts() {
        seedUser(configuration.getAdminUsername(), configuration.getAdminPassword(),
            configuration.getDefaultRoles());
        for (OidcConfiguration.OidcUserAccount account : configuration.getUsers()) {
            seedUser(account.getUsername(), account.getPassword(), account.getRoles());
        }
        log.info("Seeded {} configured account(s) into oidc_user directory",
            configuration.getUsers().size() + 1);
    }

    private void seedUser(String username, String password, List<String> roles) {
        if (username == null || username.isBlank() || password == null) {
            log.warn("skipping OIDC user seed for blank account '{}'", username);
            return;
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        final String insertUser = """
            INSERT INTO oidc_user (username, name, email, roles)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (username) DO NOTHING""";
        final String insertMethod = """
            INSERT INTO oidc_user_auth_method (user_id, type, credential)
            VALUES (?, 'PASSWORD', ?) ON CONFLICT (user_id, type) DO NOTHING""";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(insertUser)) {
                ps.setString(1, username);
                ps.setString(2, username);
                ps.setString(3, username);
                ps.setString(4, objectMapper.writeValueAsString(roles));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(insertMethod)) {
                ps.setString(1, username);
                ps.setString(2, hash);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            log.warn("failed to seed OIDC user '{}': {}", username, e.getMessage());
        }
    }

    private static boolean isMissingRelation(SQLException e) {
        return e.getSQLState() != null
            && (e.getSQLState().equals("42P01") || e.getMessage() != null && e.getMessage().contains("does not exist"));
    }

    // ------------------------------------------------------------------
    // Config-only fallback (legacy path, used when tables are unavailable)
    // ------------------------------------------------------------------

    private boolean validateFromConfig(String username, String password) {
        if (matches(configuration.getAdminUsername(), configuration.getAdminPassword(), username, password)) {
            return true;
        }
        for (OidcConfiguration.OidcUserAccount account : configuration.getUsers()) {
            if (matches(account.getUsername(), account.getPassword(), username, password)) {
                return true;
            }
        }
        return false;
    }

    private OidcUser bySubjectFromConfig(String subject) {
        for (OidcConfiguration.OidcUserAccount account : configuration.getUsers()) {
            if (account.getUsername() != null && account.getUsername().trim().equals(subject)) {
                return new OidcUser(subject, subject, subject, account.getRoles());
            }
        }
        return new OidcUser(subject, subject, subject, configuration.getDefaultRoles());
    }

    private static boolean matches(String expectedUser, String expectedPassword, String username, String password) {
        if (expectedUser == null || expectedPassword == null) {
            return false;
        }
        return constantTimeEquals(expectedUser.trim(), username)
            && constantTimeEquals(expectedPassword, password);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }
}
