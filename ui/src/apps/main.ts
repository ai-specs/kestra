// Standalone dsh Apps entry (served at /apps/{app}/{page}, outside the Kestra /ui/ SPA).
// The page is an independent HTML shell (apps.html) gated only by the OIDC session:
// the auth-flag cookie check and API 401 both redirect to the IdP login, exactly like the
// SPA guard (utils/basicAuth.ts). Everything else — schema fetch, amis render, execution
// polling — mirrors AppView.vue so the page works without any Kestra UI chrome.
import {render as amisRender, type RenderOptions} from "amis";
import {createRoot} from "react-dom/client";
import type {Api, Payload} from "amis-core";
// amis CSS contains an IE media-query hack that breaks the vite lightningcss minifier,
// so inject it at runtime as a raw string (same trick as AppView.vue).
import amisCss from "amis/lib/themes/default.css?raw";

const AUTH_FLAG_COOKIE_NAME = "oidcAuthenticated";

function isLoggedIn(): boolean {
    return document.cookie.split("; ").includes(`${AUTH_FLAG_COOKIE_NAME}=true`);
}

function redirectToLogin() {
    const from = encodeURIComponent(window.location.pathname + window.location.search);
    window.location.assign(`/oidc/login?from=${from}`);
}

function getCsrfToken(): string | null {
    return document.querySelector("meta[name=\"csrf-token\"]")?.getAttribute("content") ?? null;
}

function ensureAmisStyle() {
    if (document.getElementById("dsh-apps-amis-style")) return;
    const style = document.createElement("style");
    style.id = "dsh-apps-amis-style";
    // Layout height fix: with little content the amis Layout collapses (aside bg height 0,
    // content leaves blank space at the bottom). Stretch it to the viewport like a real
    // back-office shell.
    style.textContent = `${amisCss}
.cxd-Layout { min-height: 100vh; }
.cxd-Layout-aside,
.cxd-Layout-asideInner { height: auto !important; min-height: calc(100vh - 50px); }
.cxd-Layout-asideInner { overflow-y: auto; }
`;
    document.head.appendChild(style);
}

const POLL_INTERVAL_MS = 1000;
const POLL_TIMEOUT_MS = 30000;
const TERMINAL_STATES = new Set(["SUCCESS", "FAILED", "KILLED", "WARNING"]);

// The poll URL is taken from the first response (executionUrl), never constructed
// from an out-of-band rule. Validate it before polling: it must resolve to this
// origin under /api/v1/apps/ — a malformed or hostile value must not be able to
// point polling anywhere else.
function isTrustedPollUrl(u: string): boolean {
    try {
        const url = new URL(u, window.location.origin);
        return url.origin === window.location.origin && url.pathname.startsWith("/api/v1/apps/");
    } catch {
        return false;
    }
}

async function pollExecution(pollUrl: string): Promise<unknown> {
    const deadline = Date.now() + POLL_TIMEOUT_MS;
    // eslint-disable-next-line no-constant-condition
    while (true) {
        const r = await fetch(pollUrl, {
            headers: {"Accept": "application/json"},
            credentials: "include",
        });
        if (r.ok) {
            const d = await r.json().catch(() => null);
            // executionState is the unified field name for BOTH response bodies
            // (KESTRA {executionId, executionState, outputs, error} and AMIS
            // {executionId, executionState, status, msg, data}); the old `state`
            // field no longer exists, so polling on it never terminates.
            const state = (d as {executionState?: string} | null)?.executionState;
            if (state && TERMINAL_STATES.has(state)) {
                return d;
            }
        }
        if (Date.now() >= deadline) {
            return null;
        }
        await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
    }
}

// ---- hash-based router for the amis App component (side nav) ----
// The standalone shell has no react-router, but the amis `app` renderer drives its
// side navigation through env.jumpTo() + env.watchRouteChange(): clicking a nav link
// calls jumpTo(path), the hashchange listener fans out to watchRouteChange callbacks,
// and the AppStore re-matches the active page via isCurrentUrl(). Pages live under the
// page's hash, so the /apps/{app}/{page} URL itself stays stable.
const routeListeners = new Set<() => void>();

function currentRoutePath(): string {
    const h = window.location.hash;
    if (!h || h === "#" || h === "#/") {
        return "/";
    }
    return h.startsWith("#") ? h.slice(1) : h;
}

function normalizeRoutePath(p: string): string {
    return p.startsWith("/") ? p : `/${p}`;
}

window.addEventListener("hashchange", () => {
    for (const cb of routeListeners) {
        cb();
    }
});

const env: RenderOptions = {
    fetcher: (api: Api, data?: unknown): Promise<Payload> => {
        const apiObject = typeof api === "string" ? {url: api} : api;
        const url = apiObject.url;
        const method = (apiObject.method ?? "get").toLowerCase();
        const body = data !== undefined ? data : apiObject.data;
        const headers: Record<string, string> = {"Content-Type": "application/json"};
        const csrf = getCsrfToken();
        if (csrf) headers["X-CSRF-TOKEN"] = csrf;
        return fetch(url, {
            method,
            headers,
            body: body !== undefined ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
            credentials: "include",
        }).then(async (resp) => {
            if (resp.status === 401) {
                redirectToLogin();
            }
            const text = await resp.text();
            let parsed: unknown = text;
            try {
                parsed = text ? JSON.parse(text) : null;
            } catch {
                // non-JSON body — keep the text
            }
            if (method === "post" && resp.ok && /\/api\/v1\/apps\/[^/]+\/[^/]+$/.test(url)) {
                const p = parsed as {executionUrl?: string; executionState?: string} | null;
                // Poll exactly the URL the first response told us about — never
                // reconstruct it from the request URL. Polling is triggered ONLY
                // when the response carries a trusted executionUrl AND an
                // executionState that is present and non-terminal:
                // - terminal state (SYNC 200 SUCCESS/FAILED/...) → already final, no poll
                // - no executionState → nothing to stop the poll on, no poll
                // executionId is metadata and plays no role in this decision.
                const pollUrl = p?.executionUrl;
                const state = p?.executionState;
                if (pollUrl && isTrustedPollUrl(pollUrl) && state != null && !TERMINAL_STATES.has(state)) {
                    const polled = await pollExecution(pollUrl);
                    if (polled !== null) {
                        parsed = polled;
                    }
                }
            }
            // schemaApi (amis app fetchSchema) requests: amis expects json.data
            // to be the page schema itself. Our ApiTrigger AMIS response carries
            // it as a JSON string in data.schema (fetchSchema marks its requests
            // with `_replace=1`); unwrap it here so amis can render the page.
            if (url.includes("_replace=1") && parsed && typeof parsed === "object" &&
                (parsed as {data?: {schema?: string}} | null)?.data &&
                typeof (parsed as {data?: {schema?: string}}).data?.schema === "string") {
                try {
                    parsed = JSON.parse((parsed as {data: {schema: string}}).data.schema);
                } catch {
                    // malformed schema — keep the original response
                }
            }
            return {
                ok: resp.ok,
                status: resp.status,
                data: parsed,
                msg: resp.ok ? "" : `HTTP ${resp.status}`,
                headers: resp.headers,
            };
        }).catch((err: unknown) => {
            return {
                ok: false,
                status: 500,
                data: null,
                msg: (err as Error).message ?? "Request failed",
                headers: new Headers(),
            };
        });
    },
    notify: (type: string, msg: string) => {
        if (msg) {
            console.log(`[amis:${type}] ${msg}`);
        }
    },
    jumpTo: (to: string) => {
        if (to === "goBack") {
            window.history.back();
            return;
        }
        if (/^https?:\/\//.test(to)) {
            window.location.href = to;
            return;
        }
        const p = normalizeRoutePath(to);
        if (currentRoutePath() !== p) {
            window.location.hash = p;
        }
    },
    isCurrentUrl: (to: string) => {
        if (!to) {
            return false;
        }
        const p = normalizeRoutePath(to);
        const cur = currentRoutePath();
        return cur === p || cur.startsWith(`${p}/`) || (p === "/" && cur === "/");
    },
    watchRouteChange: (cb: () => void) => {
        routeListeners.add(cb);
        return () => {
            routeListeners.delete(cb);
        };
    },
    theme: "default",
};

async function boot() {
    const match = window.location.pathname.match(/^\/apps\/([^/]+)\/([^/]+)/);
    if (!match) {
        document.getElementById("app")!.innerText = "Invalid app path. Expected /apps/{app}/{page}";
        return;
    }
    const [, appName, pageId] = match;
    if (!isLoggedIn()) {
        redirectToLogin();
        return;
    }
    ensureAmisStyle();
    let schema: unknown;
    try {
        const resp = await fetch(`/api/v1/apps/${encodeURIComponent(appName)}/${encodeURIComponent(pageId)}`, {
            headers: {"Accept": "application/json"},
            credentials: "include",
        });
        if (resp.status === 401) {
            redirectToLogin();
            return;
        }
        if (!resp.ok) {
            throw new Error(`App page returned HTTP ${resp.status}`);
        }
        schema = await resp.json();
    } catch (e) {
        document.getElementById("app")!.innerText = `Failed to load app page: ${(e as Error).message ?? e}`;
        return;
    }
    const rendered = amisRender(schema as never, {}, env);
    if (!rendered || !(rendered as {$$typeof?: unknown}).$$typeof) {
        document.getElementById("app")!.innerText = `Amis render produced no element (schema type: ${(schema as {type?: string})?.type ?? "unknown"})`;
        return;
    }
    createRoot(document.getElementById("app")!).render(rendered);
}

void boot();
