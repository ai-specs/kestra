package io.kestra.webserver.controllers.api;

import java.util.List;
import java.util.Map;

import io.micronaut.http.HttpRequest;

/**
 * The authenticated caller on the dsh ecosystem APIs, resolved from the claims that the
 * oidc-provider module's {@code OidcBearerAuthFilter} validated and stashed on the request.
 *
 * <p>
 * Two identity kinds (dsh.docx 统一认证 / 跨端同步原理):
 * <ul>
 *   <li><em>user identity</em> — authorization code + PKCE clients ({@code dsh-ui} mobile,
 *       {@code dsh-pc} PC): {@code sub} is the IdP account (e.g. {@code alice@kestra.io});
 *       session records they create are OWNED by that sub and invisible to other users;</li>
 *   <li><em>service identity</em> — client_credentials clients ({@code dsh}): {@code sub} equals
 *       the {@code client_id}; used by AIAgent containers, flows and scripts (no human owner).</li>
 * </ul>
 *
 * <p>
 * The claims travel as a plain {@link Map} on purpose: webserver and oidc-provider are wired
 * together only in the distribution assembly, not by a Gradle dependency, and webserver has no
 * Nimbus types on its compile classpath.
 */
public final class DshIdentity {

    /** Mirrors OidcBearerAuthFilter.CLAIMS_ATTRIBUTE (kept as a literal to avoid a module dependency). */
    public static final String CLAIMS_ATTRIBUTE = "io.kestra.oidc.claims";

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

    /** Returns the caller principal, or {@code null} when the filter did not authenticate (never on guarded routes). */
    public static Principal of(HttpRequest<?> request) {
        Object attribute = request.getAttribute(CLAIMS_ATTRIBUTE, Map.class).orElse(null);
        if (!(attribute instanceof Map<?, ?> claims)) {
            return null;
        }
        Object sub = claims.get("sub");
        if (sub == null) {
            return null;
        }
        List<String> roles = claims.get("roles") instanceof List<?> raw
            ? raw.stream().map(String::valueOf).toList()
            : List.of();
        Object clientId = claims.get("client_id");
        return new Principal(sub.toString(), clientId == null ? null : clientId.toString(), roles);
    }
}
