// Standalone dsh Apps entry (served at /{namespace}/{app}/{page}, e.g. /dsh.apps/hello/index,
// outside the Kestra /ui/ SPA). Legacy /apps/{app}/{page} URLs still parse — the "apps"
// segment maps to the convention root namespace (apps.files.root-namespace, default dsh.apps).
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
// app 菜单/页面里的 fa 图标依赖 fontawesome；缺它图标整体隐形（白底白字不可见）
import "@fortawesome/fontawesome-free/css/all.css";
import "@fortawesome/fontawesome-free/css/v4-shims.css";

const AUTH_FLAG_COOKIE_NAME = "oidcAuthenticated";

function isLoggedIn(): boolean {
    return document.cookie.split("; ").includes(`${AUTH_FLAG_COOKIE_NAME}=true`);
}

function redirectToLogin() {
    // 带 hash（如 /apps/{app}/index#/schema），登录后原样回到目标页
    const from = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash);
    window.location.assign(`/oidc/login?from=${from}`);
}

function getCsrfToken(): string | null {
    return document.querySelector("meta[name=\"csrf-token\"]")?.getAttribute("content") ?? null;
}

// amis env.notify 的落地：api 失败（body status!=0，如同步执行 FAILED）时 amis 走
// notify('error', msg)。只 console.log 会让失败在页面上毫无反馈。
function showToast(msg: string, kind: "info" | "error"): void {
    const el = document.createElement("div");
    el.textContent = msg;
    Object.assign(el.style, {
        position: "fixed", top: "16px", left: "50%", transform: "translateX(-50%)",
        background: kind === "error" ? "#d4380d" : "#1677ff", color: "#fff",
        padding: "8px 16px", borderRadius: "4px", fontSize: "13px",
        zIndex: "99999", boxShadow: "0 2px 8px rgba(0,0,0,.15)",
    } satisfies Partial<CSSStyleDeclaration>);
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 4000);
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
/* app 侧栏菜单项：图标与文字同行（fa 图标渲染后需要行内布局，否则堆成两排） */
.cxd-Layout-aside .cxd-AsideNav-item > a { display: flex; align-items: center; gap: 8px; }
.cxd-Layout-aside .cxd-AsideNav-itemIcon { flex-shrink: 0; margin: 0; }
`;
    document.head.appendChild(style);
}

const POLL_INTERVAL_MS = 1000;
const POLL_TIMEOUT_MS = 30000;
const TERMINAL_STATES = new Set(["SUCCESS", "FAILED", "KILLED", "WARNING"]);

// The poll URL is taken from the first response (executionUrl), never constructed
// from an out-of-band rule. Validate it before polling: it must resolve to this
// origin under /api/v1/{namespace}/{app}/{apiId}/executions/ (legacy
// /api/v1/apps/... included — "apps" is a namespace segment) — a malformed or
// hostile value must not be able to point polling anywhere else.
function isTrustedPollUrl(u: string): boolean {
    try {
        const url = new URL(u, window.location.origin);
        return url.origin === window.location.origin
            && /^\/api\/v1\/[^/]+\/[^/]+\/[^/]+\/executions\//.test(url.pathname);
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
    // amis 6.x 的 wrapFetcher 只向 fetcher 传一个参数——buildApi 构建后的 api 对象
    // （fn(api)），不存在第二个参数：表单值已合并进 api.data（POST/PUT/PATCH；GET 进
    // query string）。dataType=form-data/form/json 时 data 已被序列化（FormData/字符串），
    // Content-Type 在 api.headers 里，原样透传。
    fetcher: (api: Api): Promise<Payload> => {
        const apiObject = typeof api === "string" ? {url: api} : api;
        const url = apiObject.url;
        const method = (apiObject.method ?? "get").toLowerCase();
        const apiHeaders = (apiObject.headers ?? {}) as Record<string, string>;
        const contentType = apiHeaders["Content-Type"] ?? apiHeaders["content-type"];
        const isFormData = typeof FormData !== "undefined" && apiObject.data instanceof FormData;
        // 跨源数据源发"简单请求"（无自定义头、不带 cookie）——自定义头触发 CORS
        // 预检，外部 API（如 amis 官方 mock）不允许 x-csrf-token
        let sameOrigin = true;
        try {
            sameOrigin = new URL(url, window.location.origin).origin === window.location.origin;
        } catch {
            sameOrigin = false;
        }
        const headers: Record<string, string> = {Accept: "application/json"};
        if (sameOrigin) {
            // multipart 的 boundary 由浏览器生成，手动设 Content-Type 会破坏表单
            if (!isFormData) {
                headers["Content-Type"] = contentType ?? "application/json";
            }
            const csrf = getCsrfToken();
            if (csrf) headers["X-CSRF-TOKEN"] = csrf;
        }
        let body: BodyInit | undefined;
        if (method !== "get" && apiObject.data !== undefined && apiObject.data !== null) {
            body = typeof apiObject.data === "string" || isFormData
                ? apiObject.data as BodyInit
                : JSON.stringify(apiObject.data);
        }
        return fetch(url, {
            method,
            headers,
            body,
            credentials: sameOrigin ? "include" : "omit",
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
            // On failure, surface the server's own error text (e.g. the {apiId}_result
            // convention 400 carries its explanation in the body) instead of a bare
            // "HTTP 400", so the amis toast tells the flow author what to fix.
            let msg = "";
            if (!resp.ok) {
                const p = parsed as {msg?: unknown; message?: unknown; error?: unknown} | null;
                const serverText = [p?.msg, p?.message, p?.error].find((v): v is string => typeof v === "string" && v.length > 0);
                msg = serverText ?? `HTTP ${resp.status}`;
            }
            return {
                ok: resp.ok,
                status: resp.status,
                data: parsed,
                msg,
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
            showToast(msg, type === "error" ? "error" : "info");
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
    // 新格式 /{namespace}/{app}/{page}（如 /dsh.apps/hello/index）；旧格式 /apps/{app}/{page}
    // 兼容 —— "apps" 段映射到约定根 namespace（apps.files.root-namespace，默认 dsh.apps）。
    const match = window.location.pathname.match(/^\/([^/]+)\/([^/]+)(?:\/([^/]+))?/);
    if (!match) {
        document.getElementById("app")!.innerText = "Invalid app path. Expected /{namespace}/{app}/{page}";
        return;
    }
    const namespace = match[1] === "apps" ? "dsh.apps" : match[1];
    const appName = match[2];
    const pageId = match[3] ?? "index";
    if (!isLoggedIn()) {
        redirectToLogin();
        return;
    }
    ensureAmisStyle();
    let schema: unknown;
    try {
        const resp = await fetch(`/api/v1/${encodeURIComponent(namespace)}/${encodeURIComponent(appName)}/${encodeURIComponent(pageId)}`, {
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
