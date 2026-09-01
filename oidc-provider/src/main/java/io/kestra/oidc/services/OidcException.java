package io.kestra.oidc.services;

import com.nimbusds.oauth2.sdk.ErrorObject;

/**
 * Thrown by OIDC Provider services when a request cannot be honoured. Carries the OAuth2
 * {@link ErrorObject} that should be returned to the client (e.g. {@code invalid_grant},
 * {@code invalid_client}, {@code access_denied}).
 */
public class OidcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorObject error;

    public OidcException(ErrorObject error) {
        super(error.getDescription() != null ? error.getDescription() : error.getCode());
        this.error = error;
    }

    public OidcException(ErrorObject error, Throwable cause) {
        super(error.getDescription() != null ? error.getDescription() : error.getCode(), cause);
        this.error = error;
    }

    public ErrorObject getError() {
        return error;
    }
}
