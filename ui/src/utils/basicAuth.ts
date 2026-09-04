import {apiUrlWithoutTenants} from "override/utils/route"
import {getCsrfToken} from "./csrf"
import {useClient} from "@kestra-io/kestra-sdk"

// Under the dsh OIDC deployment the server session is the IdP's `oidc_session` cookie
// (HttpOnly, Max-Age = session TTL) plus the `JWT` cookie Kestra's SecurityFilter validates.
// The server issues a non-HttpOnly flag cookie in lockstep — same name as the session it
// mirrors, same Max-Age — so the UI can read the login state client-side; it carries no
// credentials.
const AUTH_FLAG_COOKIE_NAME = "oidcAuthenticated"
// Upstream OSS flag name, retired by the OIDC unification. The server never sets it anymore
// (only clears it); kept here so browsers logged in before the upgrade are not kicked out
// mid-session — their legacy flag still counts as logged-in until the next login.
const LEGACY_FLAG_COOKIE_NAME = "kestraBasicAuthenticated"

// Whether this instance authenticates through the OIDC provider (/configs/login
// `oidcAuthEnabled`). When true, every not-logged-in path must land on the IdP login page —
// the Basic Auth SPA login route has no backing endpoint under OIDC, and ending up there
// after an IdP login is a hard bug. Defaults to TRUE because this fork's deployment is
// OIDC-only: before the first /configs/login answers (or when it fails, e.g. 401 on a dead
// session) the UI must assume OIDC so no failure path can ever fall through to the Basic
// Auth login page. A plain OSS backend flips it to false via the config response.
let oidcAuthEnabled = true

export function setOidcAuthEnabled(enabled: boolean) {
    oidcAuthEnabled = enabled
}

export function isOidcAuthEnabled() {
    return oidcAuthEnabled
}

/**
 * Sends the browser to the IdP login page. `fromPath` is preserved so a successful login
 * lands back on the page the user asked for. It may be either a server-absolute path
 * (`/ui/main/...`, e.g. from `window.location.pathname`) or a router path (`/main/...`,
 * e.g. `to.fullPath`, which excludes the UI base) — deep links must carry the UI base or
 * the post-login redirect lands outside the app. A hard navigation, not SPA routing: the
 * login page is server-rendered outside the app, and the login re-issues the session/JWT/
 * flag cookies.
 */
export function idpLogin(fromPath?: string) {
    let from = ""
    if (fromPath && fromPath.startsWith("/") && !fromPath.startsWith("//") && !fromPath.includes("://")) {
        const rawBase = (typeof window !== "undefined" && (window as unknown as {KESTRA_UI_PATH?: string}).KESTRA_UI_PATH) || "/ui/"
        const base = rawBase.endsWith("/") ? rawBase.slice(0, -1) : rawBase
        const absolute = fromPath === base || fromPath.startsWith(`${base}/`) ? fromPath : `${base}${fromPath}`
        from = `?from=${encodeURIComponent(absolute)}`
    }
    window.location.assign(`/oidc/login${from}`)
}

function hasFlagCookie(name: string) {
    return document.cookie
        .split("; ")
        .includes(`${name}=true`)
}

export async function logout() {
    // Clear the client-readable flags too, so isLoggedIn() flips immediately even if the
    // POST fails (under OIDC the OSS /logout endpoint has no backing bean — the real logout
    // is /oidc/logout, used by the account menu).
    for (const name of [AUTH_FLAG_COOKIE_NAME, LEGACY_FLAG_COOKIE_NAME]) {
        document.cookie = `${name}=; Max-Age=0; path=/; SameSite=Strict`
    }
    try {
        await fetch(`${apiUrlWithoutTenants()}/logout`, {
            method: "POST",
            credentials: "include",
            headers: {"X-CSRF-TOKEN": getCsrfToken() ?? ""},
        })
    } catch {
        // best-effort: if this fails, the cookies (and thus isLoggedIn()) remain as the server last set them
    }
    return true
}

export async function signIn(credentials: {username: string, password: string}) {
    const {username, password} = credentials
    const trimmedUsername = username.trim()
    await validateCredentials(trimmedUsername, password)
    return {username: trimmedUsername}
}

export function isLoggedIn() {
    return hasFlagCookie(AUTH_FLAG_COOKIE_NAME) || hasFlagCookie(LEGACY_FLAG_COOKIE_NAME)
}

async function validateCredentials(username: string, password: string) {
    try {
        const axios = useClient()
        await axios.post(`${apiUrlWithoutTenants()}/login`, {username, password}, {timeout: 10000, withCredentials: true})
    } catch(e) {
        await logout()
        throw e
    }
}
