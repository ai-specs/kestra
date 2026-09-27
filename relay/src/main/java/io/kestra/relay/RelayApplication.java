package io.kestra.relay;

import io.micronaut.runtime.Micronaut;

/**
 * dsh relay standalone server (2026-09-27): an isolated Micronaut process that owns ONLY the
 * dsh PC ⇄ Phone 联动转发 surface (DshRelayController), on its own port (default 8090).
 *
 * <p>
 * Why a separate process instead of a second listener on the Kestra webserver (8080):
 * <ul>
 *   <li>Micronaut 5 has no per-server route isolation — a second port would expose every
 *       controller; the relay process only scans {@code io.kestra.relay.*};</li>
 *   <li>the relay's Netty TCP keep-alive (半开断线检测, docs/upstream-sync.md 2026-09-27) is
 *       configured on THIS server only and cannot touch the main Kestra listener;</li>
 *   <li>high-frequency phone polling traffic stays off the Kestra UI/API/IdP port.</li>
 * </ul>
 *
 * <p>
 * Authentication is self-contained (RelayOidcAuthFilter + RelayOidcTokenService): it validates
 * access tokens issued by the Kestra OIDC provider — RS256 against the active public key stored
 * in {@code oidc_jwk} plus revocation/introspection against {@code oidc_token} — WITHOUT pulling
 * the oidc-provider module (which would auto-register its login/discovery/user-admin endpoints
 * on this port). The caller identity is stashed as {@code io.kestra.relay.claims} and read by
 * {@link io.kestra.relay.DshIdentity}.
 */
public class RelayApplication {
    public static void main(String[] args) {
        Micronaut.run(RelayApplication.class);
    }
}
