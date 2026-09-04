/**
 * dsh OIDC session helpers for the custom user-directory pages.
 *
 * Why this exists: the dsh pages (Users / Roles) call the OIDC admin API with their own
 * fetch wrapper instead of Kestra's axios client, so Kestra's central 401 → login redirect
 * does not apply. When the IdP session is gone (JWT / oidc_session expired or revoked) the
 * admin API answers 401 and the only recovery is a fresh IdP login.
 *
 * The two statuses must NOT be conflated:
 *   - 401 = session expired → go to the IdP login page carrying the current path as the
 *     `from` deep link, so the re-login lands back here. The login re-issues the
 *     oidc_session / JWT / flag cookies, superseding any stale ones.
 *   - 403 = authenticated but lacks the admin role → keep the user on the page and show
 *     a permission message (handled by the caller).
 */

export class SessionExpiredError extends Error {
    constructor() {
        super("SESSION_EXPIRED")
        this.name = "SessionExpiredError"
    }
}

let redirecting = false;

/**
 * Clears the client-readable login flags and navigates to the IdP login page with the
 * current path preserved as the `from` deep link. Guarded so concurrent 401 responses
 * only trigger one navigation.
 */
export function sessionExpired(): never {
    if (!redirecting) {
        redirecting = true;
        // The login flags are non-HttpOnly mirrors of the server session; clear them so a
        // half-completed navigation cannot flash the app as logged-in. The IdP login
        // re-issues every cookie (oidc_session / JWT / oidcAuthenticated).
        for (const name of ["oidcAuthenticated", "kestraBasicAuthenticated"]) {
            document.cookie = `${name}=; Max-Age=0; path=/; SameSite=Strict`;
        }
        // Full-page navigation (not SPA routing): the login page is server-rendered and the
        // login re-issues all session cookies.
        window.location.assign(`/oidc/login?from=${encodeURIComponent(window.location.pathname + window.location.search)}`);
    }
    throw new SessionExpiredError();
}
