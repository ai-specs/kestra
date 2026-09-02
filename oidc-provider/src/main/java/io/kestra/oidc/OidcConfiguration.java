package io.kestra.oidc;

import java.time.Duration;
import java.util.List;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * OIDC Provider configuration ({@code kestra.oidc.*}).
 *
 * <p>
 * The issuer is the externally visible base URL of the OIDC endpoints. It is used as the
 * {@code iss} claim of every issued token and as the {@code issuer} field of the discovery
 * document. In the docker-compose topology the internal service name ({@code http://kestra:8080})
 * is used so that Nacos and the Kestra self-bootstrap client can resolve it; host-side clients
 * still reach the same endpoints through the published port ({@code http://localhost:18080}).
 */
@ConfigurationProperties("kestra.oidc")
public class OidcConfiguration {

    private boolean enabled = true;
    private String issuer = "http://localhost:18080";
    private String externalBaseUrl = "http://localhost:18080";
    private List<String> defaultRoles = List.of("admin");
    private Duration authorizationCodeTtl = Duration.ofMinutes(5);
    private Duration accessTokenTtl = Duration.ofHours(1);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private String loginUrl = "/oidc/login";
    /** The provider's own administrator account (independent from Kestra's Basic Auth). */
    private String adminUsername = "admin@kestra.io";
    private String adminPassword = "Admin1234!";
    private Duration sessionTtl = Duration.ofHours(8);
    /** Additional IdP accounts beyond the administrator (dsh.docx: IdP 维护独立账号体系). */
    private List<OidcUserAccount> users = List.of();
    /**
     * Browser origins allowed to call the dsh ecosystem APIs (CORS preflight on
     * {@code /api/v1/dsh/**} is answered by OidcBearerAuthFilter itself — the generic CORS
     * filter ordering interacts badly with the route guard, and the dsh surfaces must stay
     * deterministic). dsh-ui H5 builds live on these origins.
     */
    private List<String> corsAllowedOrigins = List.of(
        "http://localhost:13010",
        "http://127.0.0.1:13010",
        "http://localhost:5173"
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * Browser-reachable base URL used only for the discovery document's
     * {@code authorization_endpoint}. In the docker-compose topology the issuer is the internal
     * service name ({@code http://kestra:8080}) that Nacos can resolve for token/jwks/userinfo,
     * while the browser must be redirected to a host-side address ({@code http://localhost:18080}).
     */
    public String getExternalBaseUrl() {
        return externalBaseUrl;
    }

    public void setExternalBaseUrl(String externalBaseUrl) {
        this.externalBaseUrl = externalBaseUrl;
    }

    public List<String> getDefaultRoles() {
        return defaultRoles;
    }

    public void setDefaultRoles(List<String> defaultRoles) {
        this.defaultRoles = defaultRoles;
    }

    public Duration getAuthorizationCodeTtl() {
        return authorizationCodeTtl;
    }

    public void setAuthorizationCodeTtl(Duration authorizationCodeTtl) {
        this.authorizationCodeTtl = authorizationCodeTtl;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    /**
     * The provider's own administrator account used by the IdP login form
     * ({@code POST /oidc/login}). This is an independent credential store — it has nothing to do
     * with Kestra's Basic Auth. Basic Auth is deliberately not used anywhere in this provider:
     * credentials are submitted once through the login form and exchanged for a server-side
     * session cookie, never re-sent on every request.
     */
    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public List<OidcUserAccount> getUsers() {
        return users;
    }

    public void setUsers(List<OidcUserAccount> users) {
        this.users = users == null ? List.of() : users;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
    }

    /**
     * One IdP account: username (= the OIDC {@code sub}), password and the roles mapped into the
     * {@code roles} claim (session ownership and approval authorization derive from the sub).
     */
    public static class OidcUserAccount {
        private String username;
        private String password;
        private List<String> roles = List.of("user");

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? List.of("user") : roles;
        }
    }
}
