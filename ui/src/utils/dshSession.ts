/**
 * dsh OIDC session helpers for the custom user-directory pages.
 *
 * Why this exists: the dsh pages (Users / Roles) call the OIDC admin API with their own
 * fetch wrapper instead of Kestra's axios client, so Kestra's central 401 → login redirect
 * does not apply. Worse, the login state that the app checks (`kestraBasicAuthenticated`,
 * a non-HttpOnly cookie) outlives the JWT: when the access token expires the cookie is
 * still present, the app still believes it is authenticated, and the admin API answers 401.
 *
 * The two statuses must NOT be conflated:
 *   - 401 = session expired → redirect through the OIDC logout endpoint so the server
 *     clears the session cookies and lands on the IdP login page (re-login).
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
 * Clears the local login flag and navigates to /oidc/logout, which clears the
 * oidc_session + JWT cookies server-side and 301-redirects to the IdP login page.
 * Guarded so concurrent 401 responses only trigger one navigation.
 */
export function sessionExpired(): never {
    if (!redirecting) {
        redirecting = true;
        // kestraBasicAuthenticated is a non-HttpOnly flag that survives JWT expiry; clear
        // it so the app stops believing it is authenticated.
        document.cookie = "kestraBasicAuthenticated=; Max-Age=0; path=/; SameSite=Strict";
        // Full-page navigation (not SPA routing): the backend clears oidc_session + JWT
        // and 301s to /oidc/login.
        window.location.assign("/oidc/logout");
    }
    throw new SessionExpiredError();
}
