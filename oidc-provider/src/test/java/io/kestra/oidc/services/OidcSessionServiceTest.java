package io.kestra.oidc.services;

import io.kestra.oidc.OidcConfiguration;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.cookie.SameSite;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Locks the IdP session-cookie policy against regressions (AI / maintainer guard).
 *
 * <p>Background: on 2026-09-27, SameSite=Strict on every session cookie caused an infinite
 * login loop once the app and the IdP were on different sites (app on localhost, IdP on a
 * Tailscale nip.io domain today, multiple production domains later). Browsers derive the
 * redirect chain's cookie site from the chain START, so a cross-site top-level navigation
 * back from the IdP does NOT carry a Strict cookie: the freshly minted session reads as
 * logged out and the browser is bounced back to the IdP. See root repo docs/upstream-sync.md.
 */
class OidcSessionServiceTest {

    @Test
    void sessionCookieIsSameSiteLaxForCrossSiteRedirects() {
        var service = new OidcSessionService(new OidcConfiguration());
        var cookie = service.sessionCookie(HttpRequest.GET("/"), "test-session");

        // AI guard: SameSite MUST be Lax (cross-site OIDC redirect chain, multi-domain).
        // If this assertion fails, someone reverted Lax→Strict during an upstream merge.
        // Restore Lax in OidcSessionService.sessionCookie(); do not relax this test.
        assertThat(
            "AI guard: the IdP↔app OIDC redirect chain crosses sites (app on localhost, IdP on a "
                + "Tailscale nip.io / production domain). SameSite MUST be Lax so the cross-site "
                + "top-level navigation back from the IdP carries the session; Strict caused an "
                + "infinite login loop on 2026-09-27 (root repo docs/upstream-sync.md). Restore "
                + "Lax in OidcSessionService.sessionCookie — do not relax this test.",
            cookie.getSameSite(),
            is(Optional.of(SameSite.Lax)));
        assertThat("session cookie must stay HttpOnly", cookie.isHttpOnly(), is(true));
        assertThat("session cookie must live on the whole origin", cookie.getPath(), is("/"));
    }

    @Test
    void oneShotSessionCookieIsSameSiteLaxForCrossSiteRedirects() {
        var service = new OidcSessionService(new OidcConfiguration());
        var cookie = service.sessionCookie(HttpRequest.GET("/"), "test-session", java.time.Duration.ofSeconds(30));

        // Same guard as above: the one-shot variant feeds the same cross-site redirect chain.
        assertThat(
            "AI guard: the one-shot session cookie also crosses sites on the OIDC redirect chain; "
                + "SameSite MUST be Lax (Strict caused the 2026-09-27 infinite login loop, root "
                + "repo docs/upstream-sync.md). Restore Lax — do not relax this test.",
            cookie.getSameSite(),
            is(Optional.of(SameSite.Lax)));
        assertThat(cookie.isHttpOnly(), is(true));
    }
}
