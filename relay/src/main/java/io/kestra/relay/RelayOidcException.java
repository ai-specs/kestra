package io.kestra.relay;

/**
 * Bearer-token validation failure (RFC 6750 style). Mirrors oidc-provider's OidcException —
 * kept local so the relay stays independent of that module.
 */
public class RelayOidcException extends RuntimeException {
    public RelayOidcException(String message) {
        super(message);
    }

    public RelayOidcException(String message, Throwable cause) {
        super(message, cause);
    }
}
