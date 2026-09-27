package io.kestra.relay;

import java.util.List;
import java.util.Map;

import io.micronaut.http.HttpRequest;

/**
 * The authenticated caller on the relay surface, resolved from the claims that
 * {@link RelayOidcAuthFilter} validated and stashed on the request.
 *
 * <p>
 * Mirrors webserver's DshIdentity (kept local so the relay stays a self-contained module):
 * two identity kinds — <em>user identity</em> ({@code dsh-ui} mobile / {@code dsh-pc} PC,
 * authorization code + PKCE, {@code sub} = the IdP account) and <em>service identity</em>
 * ({@code dsh}, client_credentials, {@code sub} == {@code client_id}, no human owner).
 */
public final class DshIdentity {

    /** Mirrors RelayOidcAuthFilter.CLAIMS_ATTRIBUTE (kept as a literal to avoid a package cycle). */
    public static final String CLAIMS_ATTRIBUTE = "io.kestra.relay.claims";

    public record Principal(String sub, String clientId, List<String> roles) {
        public boolean isAdmin() {
            return roles != null && roles.contains("admin");
        }

        /** True for client_credentials callers (sub == client_id, no human owner). */
        public boolean isService() {
            return sub != null && sub.equals(clientId);
        }
    }

    private DshIdentity() {}

    /** Returns the caller principal, or {@code null} when the filter did not authenticate. */
    public static Principal of(HttpRequest<?> request) {
        Object attribute = request.getAttribute(CLAIMS_ATTRIBUTE, Map.class).orElse(null);
        if (!(attribute instanceof Map<?, ?> claims)) {
            return null;
        }
        Object sub = claims.get("sub");
        Object clientId = claims.get("client_id");
        if (!(sub instanceof String) || !(clientId instanceof String)) {
            return null;
        }
        List<String> roles = null;
        Object rawRoles = claims.get("roles");
        if (rawRoles instanceof List<?> list) {
            roles = list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return new Principal((String) sub, (String) clientId, roles);
    }
}
