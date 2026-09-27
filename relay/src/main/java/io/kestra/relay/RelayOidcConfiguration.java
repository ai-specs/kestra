package io.kestra.relay;

import java.util.List;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * The subset of {@code kestra.oidc.*} the relay needs to validate access tokens and answer
 * CORS preflights. Mirrors the oidc-provider module's OidcConfiguration fields; the relay does
 * NOT depend on that module (which would auto-register its controllers on this port).
 */
@ConfigurationProperties("kestra.oidc")
public class RelayOidcConfiguration {

    private String issuer = "http://localhost:18080";
    private List<String> corsAllowedOrigins = List.of();

    /** The issuer used as the {@code iss} claim of every issued token (kestra.oidc.issuer). */
    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** Browser origin allow-list for CORS preflight answers (kestra.oidc.cors-allowed-origins). */
    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
