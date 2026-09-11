// dsh Apps visual editor. One source of truth, two embedding surfaces:
//   - standalone entry (apps-editor.html) at /apps/designer (designer mode) and
//     /apps/{app}/{page}/edit (single-page edit)
//   - kestra-ui SPA /ui/main/pages → PagesEditor.vue renders an <iframe src=/apps/designer?embedded=1&theme=…>
//     so the editor runs in its own document: amis css (and any css a user schema can
//     bring) can never leak into the shell, and dark/light follows the host via postMessage.
// The editor is decoupled from flow/trigger entirely: every input is a convention path
// (apps/{app}/{page}.json) read/written through the file endpoints.
//
// The editor always runs in its own document — the standalone entry page, or the iframe
// mounted on /ui/main/pages (see PagesEditor.vue). An iframe gives true isolation: amis's
// theme css carries bare-tag / generic rules (`body`, `a`, `div`, ...) and the editor can
// render user schema that brings arbitrary css, none of which may leak into the kestra-ui
// shell. Within its own document those rules are safe, so the amis css is injected
// verbatim (no selector filtering) — matching the upstream demo rendering exactly.
// Fonts/images referenced by these stylesheets: iconfont + amis theme images are inline
// data: URIs (no fetch); fontawesome webfonts and the editor-core nav pngs are copied into
// ui/public/ (build root) and referenced by absolute /ui/ paths at injection time.
import amisCxdCss from "amis/lib/themes/cxd.css?raw";
import amisDarkCss from "amis/lib/themes/dark.css?raw";
import amisHelperCss from "amis/lib/helper.css?raw";
import amisIconfontCss from "amis/sdk/iconfont.css?raw";
import editorCoreCss from "amis-editor-core/lib/style.css?raw";
import faAllCss from "@fortawesome/fontawesome-free/css/all.css?raw";
import faShimsCss from "@fortawesome/fontawesome-free/css/v4-shims.css?raw";
import {Editor, ShortcutKey} from "amis-editor";
import {setThemeConfig} from "amis-editor-core";
import {setDefaultTheme} from "amis";
import lightThemeConfig from "amis-theme-editor-helper/lib/systemTheme/cxd";
import {createRoot} from "react-dom/client";
import {useEffect, useState} from "react";

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

// ---- execution submit polling (same contract as the render page) ----
// A POST to an app api returns 202 + {executionId, executionState, executionUrl};
// the result is only meaningful once executionState reaches a terminal value, so the
// fetcher polls the trusted executionUrl until terminal (bounded).
const POLL_INTERVAL_MS = 1000;
const POLL_TIMEOUT_MS = 30000;
const TERMINAL_STATES = new Set(["SUCCESS", "FAILED", "KILLED", "WARNING"]);

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
    for (;;) {
        const r = await fetch(pollUrl, {headers: {"Accept": "application/json"}, credentials: "include"});
        if (r.ok) {
            const d = await r.json().catch(() => null);
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

// ---- editor styles: injectable, removable, theme-swappable ----
type EditorTheme = "light" | "dark";

// The editor document is always our own (standalone entry, or the iframe on /ui/main/pages).
// Theme resolution order: ?theme= query (set by the SPA iframe host) → localStorage("theme")
// (kestra-ui persists its switch there) → system color scheme.
function resolveTheme(): EditorTheme {
    const q = new URLSearchParams(window.location.search).get("theme");
    if (q === "dark" || q === "light") {
        return q;
    }
    const stored = localStorage.getItem("theme");
    if (stored === "dark" || stored === "dark-2") {
        return "dark";
    }
    if (stored === "light") {
        return "light";
    }
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

// amis css is injected as raw text; rewrite the few external url() references to the
// build-root copies in ui/public/ (served under /ui/). iconfont fonts and amis theme
// images are inline data: URIs, so they need no rewriting.
function resolveEditorCss(raw: string): string {
    return raw
        .replaceAll("url(\"../webfonts/", "url(\"/ui/fa-webfonts/")
        .replaceAll("url(\"../static/", "url(\"/ui/amis-editor-static/");
}

const EDITOR_STYLE_ID = "dsh-apps-editor-style";
// The dsh shell chrome is injected as its own <style> element: amis css is huge and a
// single unparseable rule anywhere inside it (amis ships IE-hack `@media (min-width: 0\0)`
// rules that some parsers choke on) could swallow the rules that follow it — the shell
// rules must never depend on surviving that blob.
const EDITOR_SHELL_STYLE_ID = "dsh-apps-editor-shell-style";

// dsh-specific editor shell layout (not part of the amis theme; injected last so it wins).
const DSH_SHELL_CSS = `/* editor shell layout */
html, body { height: 100%; margin: 0; }
/* standalone entry + /ui/main/pages iframe: the editor owns the whole document, so pin
   the root to the viewport height (100vh). The legacy SPA-inline mode (.is-embedded) is
   pinned to its flex host instead. */
.dsh-editor-root { display: flex; height: 100vh; min-height: 0; overflow: hidden; }
.dsh-editor-root.is-embedded { height: 100%; min-height: 0; }
.dsh-editor-root:not(.is-embedded) .dsh-editor-shell { height: 100%; min-height: 0; }
.dsh-editor-root:not(.is-embedded) .dsh-editor-shell { height: 100vh; }
.dsh-editor-shell { display: flex; flex-direction: column; flex: 1; min-height: 0; width: 100%; }
.dsh-editor-shell .Editor-inner { flex: 1; overflow: hidden; min-height: 0; }
.dsh-editor-shell .Editor-Demo { height: 100%; display: flex; flex-direction: column; }
.dsh-editor-shell .Editor-Demo .Editor-inner { flex: 1; }
/* amis-editor 内部高度链：ae-Editor 自带 450px 默认高、内部各层不伸展，
   导致预览画布（及其中渲染的 app Layout）塌缩成内容高、下方留大片死区。
   逐层强制撑满，编辑器即可填满 Editor-inner 的全部可用高度 */
.dsh-editor-shell .ae-Editor { height: 100% !important; }
.dsh-editor-shell .ae-Main { height: 100% !important; }
.dsh-editor-shell .ae-Preview-outter,
.dsh-editor-shell .ae-Preview-body,
.dsh-editor-shell .ae-Preview-inner { height: 100% !important; }
.dsh-editor-shell .ae-Preview-body { display: flex; flex-direction: column; }
.dsh-editor-shell .ae-Preview-body .cxd-Layout { flex: 1; min-height: 0; }
/* 预览画布渲染 app 型页面（cxd-Layout 全出血侧栏）时去留白：
   ae-Preview-body 的 16px 内边距会把深色侧栏背景盒右推，而固定定位的
   侧栏菜单仍锚在视口 x=0 —— 菜单左端落在白底上（白字白底不可见） */
.dsh-editor-shell .ae-Preview-body:has(.cxd-Layout) { padding: 0 !important; }
/* 预览画布里 app 侧栏菜单项：图标与文字同行（cxd 桌面态行内布局在
   预览容器内不生效，会退化成图标一行、文字一行） */
.dsh-editor-shell .ae-Preview-body .cxd-AsideNav-item > a {
    display: flex;
    align-items: center;
    gap: 8px;
    white-space: nowrap;
    overflow: hidden;
}
.dsh-editor-shell .ae-Preview-body .cxd-AsideNav-itemIcon { flex-shrink: 0; margin: 0; }
.dsh-designer { display: flex; min-height: 0; flex: 1; overflow: hidden; }
.dsh-designer-tree { width: 260px; min-width: 260px; background: #fff; border-right: 1px solid #e8e8e8; overflow-y: auto; padding: 12px 0; }
.dsh-designer-tree h3 { font-size: 13px; color: #666; padding: 0 16px; margin: 8px 0 4px; }
.dsh-designer-main { flex: 1; overflow: hidden; display: flex; flex-direction: column; min-width: 0; }
.dsh-designer-main > .Editor-Demo { flex: 1; min-height: 0; }
.dsh-tree-node { display: block; width: 100%; text-align: left; border: none; background: none; padding: 6px 16px; font-size: 13px; color: #333; cursor: pointer; }
.dsh-tree-node:hover { background: #f2f3f7; }
/* editor header toolbar (demo-style) */
.Editor-header { display: flex; align-items: center; gap: 12px; padding: 8px 14px; background: #fff; border-bottom: 1px solid #e8e8e8; flex-wrap: wrap; }
.Editor-title { font-size: 13px; color: #444; font-weight: 500; margin-right: auto; }
.Editor-view-mode-group-container { flex-shrink: 0; }
.Editor-view-mode-group { display: inline-flex; border: 1px solid #d4d6db; border-radius: 4px; overflow: hidden; }
.Editor-view-mode-btn { border: none; background: #fff; padding: 4px 12px; font-size: 13px; color: #585858; cursor: pointer; }
.Editor-view-mode-btn + .Editor-view-mode-btn { border-left: 1px solid #d4d6db; }
.Editor-view-mode-btn:hover { background: #f2f3f7; }
.Editor-view-mode-btn.is-active { background: #0057ff; color: #fff; }
.Editor-header-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.header-action-btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid #d4d6db; border-radius: 4px; background: #fff; padding: 4px 14px; font-size: 13px; color: #333; cursor: pointer; text-decoration: none; line-height: 20px; }
.header-action-btn:hover { border-color: #0057ff; color: #0057ff; }
.header-action-btn.primary { background: #0057ff; border-color: #0057ff; color: #fff; }
.header-action-btn.primary:hover { background: #0047d0; color: #fff; }
.dsh-tree-node.is-active { background: #e8f1ff; color: #1677ff; }
.dsh-tree-node.is-app { font-weight: 600; }
.dsh-tree-node.is-page { padding-left: 36px; }
.dsh-tree-node.is-page3 { padding-left: 56px; }
.dsh-tree-node .dsh-tree-index { color: #1677ff; font-size: 11px; border: 1px solid #1677ff; border-radius: 2px; padding: 0 3px; margin-left: 6px; }
.dsh-tree-empty { padding: 24px 16px; color: #999; font-size: 13px; line-height: 1.8; }
.dsh-tree-warning { margin: 4px 12px; padding: 6px 8px; background: #fff7e6; border: 1px solid #ffd591; border-radius: 4px; color: #d46b08; font-size: 12px; }
.dsh-designer-placeholder { flex: 1; display: flex; align-items: center; justify-content: center; color: #999; font-size: 14px; min-height: 0; }
.dsh-editor-root.is-embedded .dsh-designer-placeholder { flex: 1; }
/* dark theme compatibility: amis/editor-core surfaces follow dark.css :root variables,
   but the dsh shell chrome (our own classes) needs explicit dark overrides */
html.dark .dsh-designer-tree { background: #1d1e22; border-right-color: #303136; }
html.dark .dsh-designer-tree h3 { color: #9aa0aa; }
html.dark .dsh-tree-node { color: #d5d7dc; }
html.dark .dsh-tree-node:hover { background: #282a30; }
html.dark .dsh-tree-node.is-active { background: #12253f; color: #5ab0ff; }
html.dark .dsh-tree-node .dsh-tree-index { color: #5ab0ff; border-color: #5ab0ff; }
html.dark .dsh-tree-empty { color: #7a7f88; }
html.dark .dsh-tree-warning { background: #2b2410; border-color: #6b5412; color: #e8b339; }
html.dark .Editor-header { background: #1d1e22; border-bottom-color: #303136; }
html.dark .Editor-title { color: #c9ccd2; }
html.dark .Editor-view-mode-group { border-color: #3c3f46; }
html.dark .Editor-view-mode-btn { background: #24262b; color: #b6bac2; }
html.dark .Editor-view-mode-btn + .Editor-view-mode-btn { border-left-color: #3c3f46; }
html.dark .Editor-view-mode-btn:hover { background: #2f3238; }
html.dark .Editor-view-mode-btn.is-active { background: #0057ff; color: #fff; }
html.dark .header-action-btn { background: #24262b; border-color: #3c3f46; color: #d5d7dc; }
html.dark .header-action-btn:hover { border-color: #5ab0ff; color: #5ab0ff; }
html.dark .header-action-btn.primary { background: #0057ff; border-color: #0057ff; color: #fff; }
html.dark .dsh-designer-placeholder { color: #7a7f88; }
`;

function buildEditorStyleText(theme: EditorTheme): string {
    const amisThemeCss = theme === "dark" ? amisDarkCss : amisCxdCss;
    // Injected verbatim (no filtering): the editor document is our own (standalone page or
    // iframe), so amis's bare-tag rules can never reach the kestra-ui shell.
    return [
        resolveEditorCss(amisThemeCss),
        resolveEditorCss(amisHelperCss),
        // iconfont (.icon) and fontawesome (.fa) classes — kept whole; their rules only
        // ever target icon elements.
        amisIconfontCss,
        resolveEditorCss(editorCoreCss),
        resolveEditorCss(faAllCss),
        resolveEditorCss(faShimsCss),
    ].join("\n");
}

function ensureEditorStyle(theme: EditorTheme): void {
    let style = document.getElementById(EDITOR_STYLE_ID) as HTMLStyleElement | null;
    if (!style) {
        style = document.createElement("style");
        style.id = EDITOR_STYLE_ID;
        document.head.appendChild(style);
    }
    style.textContent = buildEditorStyleText(theme);
    let shell = document.getElementById(EDITOR_SHELL_STYLE_ID) as HTMLStyleElement | null;
    if (!shell) {
        shell = document.createElement("style");
        shell.id = EDITOR_SHELL_STYLE_ID;
        document.head.appendChild(shell);
    }
    shell.textContent = DSH_SHELL_CSS;
}

function removeEditorStyle(): void {
    document.getElementById(EDITOR_STYLE_ID)?.remove();
    document.getElementById(EDITOR_SHELL_STYLE_ID)?.remove();
}

function applyEditorTheme(theme: EditorTheme): void {
    ensureEditorStyle(theme);
    // The dsh shell css (and any amis rule) keys dark chrome off `html.dark`. The editor
    // document is ours (standalone entry or the /ui/main/pages iframe), so toggling the
    // class only ever affects this document — never the kestra-ui shell.
    document.documentElement.classList.toggle("dark", theme === "dark");
    // amis-theme-editor-helper ships no dark preset (only cxd/antd/component); dark is
    // driven by injecting amis/lib/themes/dark.css, whose :root variables also recolor
    // the editor-core chrome. setThemeConfig stays on the cxd preset for the design
    // surface (property panels etc.) in both themes.
    setDefaultTheme(theme === "dark" ? "dark" : "cxd");
    setThemeConfig(lightThemeConfig);
}

// Theme sync. The editor document is always our own, so there is no host html class to
// observe; instead:
//   - the /ui/main/pages iframe host sends {type:"dsh-editor-theme", theme} via postMessage
//     (initial theme also arrives as ?theme= in the src), and kestra-ui persists switches
//     to localStorage("theme"), which fires the same-origin `storage` event here;
//   - the standalone entry just resolves from query/localStorage/system once.
function useHostTheme(embedded: boolean | undefined): EditorTheme {
    const [theme, setTheme] = useState<EditorTheme>(resolveTheme);
    useEffect(() => {
        const apply = () => setTheme(resolveTheme());
        const onMessage = (e: MessageEvent) => {
            if (e.origin !== window.location.origin) {
                return;
            }
            const d = e.data as {type?: string; theme?: string} | null;
            if (d?.type === "dsh-editor-theme" && (d.theme === "dark" || d.theme === "light")) {
                setTheme(d.theme);
            }
        };
        window.addEventListener("storage", apply);
        window.addEventListener("message", onMessage);
        return () => {
            window.removeEventListener("storage", apply);
            window.removeEventListener("message", onMessage);
        };
    }, [embedded]);
    return theme;
}

interface ApiResult {
    ok: boolean;
    status: number;
    data: unknown;
    msg: string;
}

async function apiRequest(
    url: string,
    method: "GET" | "POST" | "PUT" | "DELETE",
    body?: unknown,
    contentType?: string,
): Promise<ApiResult> {
    // 同源请求带 CSRF + credentials（平台会话契约）；跨源请求发"简单请求"
    // （不加自定义头、不带 cookie）——自定义头会触发 CORS 预检，外部数据源
    // （如 amis 官方 mock）不允许 x-csrf-token，预检失败页面数据就加载不出。
    let sameOrigin = true;
    try {
        sameOrigin = new URL(url, window.location.origin).origin === window.location.origin;
    } catch {
        sameOrigin = false;
    }
    // multipart 的 boundary 由浏览器生成，手动设 Content-Type 会破坏表单
    const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
    const headers: Record<string, string> = {Accept: "application/json"};
    if (sameOrigin) {
        if (!isFormData) {
            headers["Content-Type"] = contentType ?? "application/json";
        }
        const csrf = getCsrfToken();
        if (csrf) {
            headers["X-CSRF-TOKEN"] = csrf;
        }
    }
    let payload: BodyInit | undefined;
    if (body !== undefined && body !== null) {
        if (typeof body === "string" || isFormData) {
            payload = body as BodyInit;
        } else if (method !== "GET") {
            payload = JSON.stringify(body);
        }
    }
    try {
        const resp = await fetch(url, {
            method,
            headers,
            body: payload,
            credentials: sameOrigin ? "include" : "omit",
        });
        if (resp.status === 401) {
            redirectToLogin();
        }
        const text = await resp.text();
        let data: unknown = text;
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            // keep raw text
        }
        return {ok: resp.ok, status: resp.status, data, msg: resp.ok ? "" : `HTTP ${resp.status}`};
    } catch (err) {
        return {ok: false, status: 500, data: null, msg: (err as Error).message ?? "Request failed"};
    }
}

function filePath(appName: string, page: string): string {
    return `apps/${appName}/${page}.json`;
}

function encodePath(p: string): string {
    return p.split("/").map(encodeURIComponent).join("/");
}

function toast(msg: string, background = "#1677ff") {
    // Minimal inline toast (no amis dependency for the editor shell).
    const el = document.createElement("div");
    el.className = "dsh-toast";
    el.textContent = msg;
    Object.assign(el.style, {
        position: "fixed", top: "16px", left: "50%", transform: "translateX(-50%)",
        background, color: "#fff", padding: "8px 16px", borderRadius: "4px",
        fontSize: "13px", zIndex: "99999", boxShadow: "0 2px 8px rgba(0,0,0,.15)",
    });
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 4000);
}

// ---- single-page editor (also used inside designer mode) ----
function PageEditor({appName, page, embedded}: {appName: string; page: string; embedded?: boolean}) {
    const [schema, setSchema] = useState<unknown>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    // 编辑入口默认编辑模式（组件库 + 属性面板）；预览经头部按钮切换
    const [preview, setPreview] = useState(false);
    const [isMobile, setIsMobile] = useState(false);
    const theme = useHostTheme(embedded);
    const path = filePath(appName, page);

    // keep the injected amis stylesheet + amis runtime theme in sync with the host theme
    useEffect(() => {
        applyEditorTheme(theme);
    }, [theme]);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        (async () => {
            const r = await apiRequest(`/api/v1/apps/files?path=${encodePath(path)}`, "GET");
            if (cancelled) {
                return;
            }
            if (r.ok) {
                setSchema(r.data);
            } else if (r.status === 404) {
                // 约定外/未创建页面：初始化空页，首次保存创建文件（§6.4）
                setSchema({type: "page", body: []});
            } else {
                setError(r.msg);
            }
            setLoading(false);
        })();
        return () => {
            cancelled = true;
        };
    }, [path]);

    // amis App (type:"app") schemas route off window.location.hash; with no hash the
    // canvas renders amis's NotFound. Seed the landing page url so the canvas shows the
    // app's default page (the iframe/standalone url never carries a hash on entry).
    // Pages may be nested in groups (children[]), so the url search must recurse.
    useEffect(() => {
        if (!schema || typeof schema !== "object" || Array.isArray(schema)) {
            return;
        }
        const s = schema as {type?: string; pages?: Array<{url?: string; isDefault?: boolean; children?: unknown[]}>};
        if (s.type !== "app" || !Array.isArray(s.pages) || window.location.hash) {
            return;
        }
        const findUrl = (nodes: Array<{url?: string; isDefault?: boolean; children?: unknown[]}>): string | undefined => {
            const byDefault = (ns: Array<{url?: string; isDefault?: boolean; children?: unknown[]}>): string | undefined => {
                for (const n of ns) {
                    if (n.isDefault && n.url) {
                        return n.url;
                    }
                }
                return undefined;
            };
            const first = (ns: Array<{url?: string; isDefault?: boolean; children?: unknown[]}>): string | undefined => {
                for (const n of ns) {
                    if (n.url) {
                        return n.url;
                    }
                    const u = n.children ? first(n.children as Array<{url?: string; isDefault?: boolean; children?: unknown[]}>) : undefined;
                    if (u) {
                        return u;
                    }
                }
                return undefined;
            };
            return byDefault(nodes) ?? first(nodes);
        };
        const url = findUrl(s.pages);
        if (url) {
            window.location.hash = url;
        }
    }, [schema]);

    async function save() {
        const r = await apiRequest(`/api/v1/apps/files?path=${encodePath(path)}`, "PUT", schema as object);
        if (r.ok) {
            toast(`已保存 ${path}`);
        } else {
            toast(`保存失败：${r.msg}`);
        }
    }

    if (loading) {
        return <div className="dsh-designer-placeholder">加载中…</div>;
    }
    if (error) {
        return <div className="dsh-designer-placeholder">{error}</div>;
    }

    return (
        <div className={`dsh-editor-root AMISCSSWrapper ${embedded ? "is-embedded" : ""}`}>
            <div className="dsh-editor-shell">
                <div className="Editor-Demo">
                    <div className="Editor-header">
                        <div className="Editor-title">页面编辑器：{path}</div>
                        <div className="Editor-view-mode-group-container">
                            <div className="Editor-view-mode-group">
                                <button
                                    className={`Editor-view-mode-btn ${!isMobile ? "is-active" : ""}`}
                                    onClick={() => setIsMobile(false)}
                                >
                                    PC
                                </button>
                                <button
                                    className={`Editor-view-mode-btn ${isMobile ? "is-active" : ""}`}
                                    onClick={() => setIsMobile(true)}
                                >
                                    H5
                                </button>
                            </div>
                        </div>
                        <div className="Editor-header-actions">
                            <ShortcutKey />
                            <button className="header-action-btn" onClick={() => setPreview(!preview)}>
                                {preview ? "编辑" : "预览"}
                            </button>
                            <button className="header-action-btn primary" onClick={save}>
                                保存
                            </button>
                            <a className="header-action-btn exit-btn" href={`/apps/${appName}/${page}`} target="_top">
                                预览
                            </a>
                        </div>
                    </div>
                    <div className="Editor-inner">
                        <Editor
                            key={theme}
                            theme={theme === "dark" ? "dark" : "cxd"}
                            preview={preview}
                            isMobile={isMobile}
                            value={schema}
                            onChange={(v: unknown) => setSchema(v)}
                            onSave={save}
                            amisEnv={{
                                // amis 6.x 的 wrapFetcher 只向 fetcher 传一个参数——buildApi
                                // 构建后的 api 对象（fn(api)），不存在第二个参数：表单值已合并
                                // 进 api.data（POST/PUT/PATCH；GET 进 query string），读第二参
                                // 恒得 undefined → 恒空体提交。dataType=form-data/form/json 时
                                // data 已被序列化（FormData/字符串），Content-Type 在 api.headers。
                                fetcher: (api: unknown) => {
                                    const apiObject = typeof api === "string"
                                        ? {url: api, method: "get"}
                                        : (api as {url: string; method?: string; data?: unknown; headers?: Record<string, string>});
                                    const method = (apiObject.method ?? "get").toUpperCase() as "GET" | "POST" | "PUT" | "DELETE";
                                    const apiHeaders = apiObject.headers ?? {};
                                    const contentType = apiHeaders["Content-Type"] ?? apiHeaders["content-type"];
                                    const body = method === "GET" ? undefined : apiObject.data;
                                    return apiRequest(apiObject.url, method, body, contentType).then(async (resp) => {
                                        // app api 提交：202 + executionUrl + 非终态 → 自动轮询到终态再返回
                                        if (method === "POST" && resp.ok && /\/api\/v1\/apps\/[^/]+\/[^/]+$/.test(apiObject.url)) {
                                            const payload = resp.data as {executionUrl?: string; executionState?: string} | null;
                                            const pollUrl = payload?.executionUrl;
                                            const state = payload?.executionState;
                                            if (pollUrl && isTrustedPollUrl(pollUrl) && state && !TERMINAL_STATES.has(state)) {
                                                const polled = await pollExecution(pollUrl);
                                                if (polled !== null) {
                                                    resp.data = polled;
                                                }
                                            }
                                        }
                                        return resp;
                                    });
                                },
                                notify: (type: string, msg: string) => {
                                    if (msg) {
                                        console.log(`[amis:${type}] ${msg}`);
                                        // 失败必须可见：api body status!=0 时 amis 走 notify('error')
                                        toast(msg, type === "error" ? "#d4380d" : "#1677ff");
                                    }
                                },
                                alert: (msg: string) => toast(msg),
                                copy: (text: string) => {
                                    void navigator.clipboard?.writeText(text).catch(() => undefined);
                                },
                                jumpTo: (to: string) => {
                                    if (/^https?:\/\//.test(to)) {
                                        window.open(to, "_blank", "noopener");
                                        return;
                                    }
                                    window.location.hash = to.startsWith("/") || to.startsWith("#") ? to : `/${to}`;
                                },
                                isCurrentUrl: (to: string) => {
                                    if (!to) return false;
                                    const cur = window.location.hash.slice(1);
                                    const t = to.startsWith("/") || to.startsWith("#") ? to.replace(/^#/, "") : `/${to}`;
                                    return cur === t || cur.startsWith(`${t}/`);
                                },
                                watchRouteChange: (cb: () => void) => {
                                    const h = () => cb();
                                    window.addEventListener("hashchange", h);
                                    return () => window.removeEventListener("hashchange", h);
                                },
                            }}
                        />
                    </div>
                </div>
            </div>
        </div>
    );
}

// ---- page tree (designer mode) ----
interface TreeNode {
    name: string;
    kind: "page" | "group";
    index?: boolean;
    children?: TreeNode[];
}

interface AppNode {
    appName: string;
    namespace?: string | null;
    warning?: string | null;
    pages: TreeNode[];
}

function Designer({embedded}: {embedded?: boolean}) {
    const [apps, setApps] = useState<AppNode[] | null>(null);
    const [selected, setSelected] = useState<{appName: string; page: string} | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            const r = await apiRequest("/api/v1/apps/pages", "GET");
            if (cancelled) {
                return;
            }
            if (r.ok) {
                setApps(r.data as AppNode[]);
            } else {
                setError(r.msg);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);

    function selectPage(appName: string, page: string) {
        setSelected({appName, page});
    }

    function renderChildren(appName: string, nodes: TreeNode[] | undefined, depth: number) {
        if (!nodes || nodes.length === 0) {
            return null;
        }
        return nodes.map(node => {
            const cls = depth === 0 ? "dsh-tree-node is-page" : "dsh-tree-node is-page3";
            if (node.kind === "page") {
                const active = selected?.appName === appName && selected.page === node.name;
                return (
                    <button
                        key={node.name}
                        className={`${cls} ${active ? "is-active" : ""}`}
                        onClick={() => selectPage(appName, node.name)}
                    >
                        {node.name}
                        {node.index ? <span className="dsh-tree-index">首页</span> : null}
                    </button>
                );
            }
            // group: its own page (if same-name file exists → kind was upgraded to "page"
            // with children; a pure dir stays "group") — render the folder label.
            return (
                <div key={node.name}>
                    <div className="dsh-tree-node">{node.name}/</div>
                    {renderChildren(appName, node.children, depth + 1)}
                </div>
            );
        });
    }

    if (error) {
        return <div className="dsh-designer-placeholder">{error}</div>;
    }
    if (apps === null) {
        return <div className="dsh-designer-placeholder">加载中…</div>;
    }

    return (
        <div className={`dsh-editor-root AMISCSSWrapper ${embedded ? "is-embedded" : ""}`}>
            <div className="dsh-designer">
                <div className="dsh-designer-tree">
                    <h3>App 设计器</h3>
                    {apps.length === 0 && <div className="dsh-tree-empty">约定目录 apps/ 下暂无页面。<br/>在目录页进入某页面的「设计」入口后首次保存会自动创建文件。</div>}
                    {apps.map(app => (
                        <div key={app.appName}>
                            <div className="dsh-tree-node is-app">📁 {app.appName}</div>
                            {app.warning ? <div className="dsh-tree-warning">{app.warning}</div> : null}
                            {renderChildren(app.appName, app.pages, 0)}
                        </div>
                    ))}
                </div>
                <div className="dsh-designer-main">
                    {selected ? (
                        <PageEditor appName={selected.appName} page={selected.page} embedded={embedded} />
                    ) : (
                        <div className="dsh-designer-placeholder">选择左侧一个页面开始编辑</div>
                    )}
                </div>
            </div>
        </div>
    );
}

// ---- legacy SPA-inline mount contract ----
// Kept for compatibility; the SPA surface (/ui/main/pages) now embeds the editor as an
// <iframe> instead (true CSS isolation), so PagesEditor.vue no longer calls this.
export interface MountEditorOptions {
    mode: "designer" | "page";
    appName?: string;
    page?: string;
    /** true when mounted inside the kestra-ui SPA (height 100% of the content area). */
    embedded?: boolean;
}

export function mountEditor(container: HTMLElement, opts: MountEditorOptions): () => void {
    applyEditorTheme(resolveTheme());
    const root = createRoot(container);
    if (opts.mode === "designer") {
        root.render(<Designer embedded={opts.embedded} />);
    } else {
        root.render(<PageEditor appName={opts.appName ?? "hello"} page={opts.page ?? "index"} embedded={opts.embedded} />);
    }
    return () => {
        root.unmount();
        // the injected stylesheet must not leak into other SPA pages
        removeEditorStyle();
    };
}

// ---- boot (standalone entry: /apps/designer and /apps/{app}/{page}/edit) ----
// The SPA-inline bundle imports this module too (PagesEditor.vue → mountEditor); boot must
// only run on the standalone entry URLs, otherwise it would grab the SPA's own #app root.
function boot() {
    const path = window.location.pathname;
    const isStandalone =
        path === "/apps/designer" ||
        path.startsWith("/apps/designer/") ||
        /^\/apps\/[^/]+\/(.+?)\/edit$/.test(path);
    if (!isStandalone) {
        return;
    }

    if (!isLoggedIn()) {
        redirectToLogin();
        return;
    }
    applyEditorTheme(resolveTheme());

    const root = document.getElementById("app")!;

    // Designer entry: /apps/designer (reserved appName, UiAppController routes it here)
    if (path === "/apps/designer" || path.startsWith("/apps/designer/")) {
        createRoot(root).render(<Designer />);
        return;
    }

    // Single-page edit: /apps/{app}/edit (page defaults to index) or
    // /apps/{app}/{page}/edit where {page} may be a group path "x/y" (third level).
    const editMatch = path.match(/^\/apps\/([^/]+)\/(.+?)\/edit$/);
    if (editMatch) {
        const appName = decodeURIComponent(editMatch[1]);
        const page = decodeURIComponent(editMatch[2]);
        createRoot(root).render(<PageEditor appName={appName} page={page} />);
        return;
    }

    root.innerText = "Invalid editor path. Expected /apps/designer or /apps/{app}/{page}/edit";
}

void boot();
