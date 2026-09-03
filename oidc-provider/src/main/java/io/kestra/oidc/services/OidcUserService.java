package io.kestra.oidc.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

    private static final Logger log = LoggerFactory.getLogger(OidcUserService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final OidcSessionService sessionService;
    private final OidcConfiguration configuration;
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
        DataSource dataSource,
        ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.configuration = configuration;
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
