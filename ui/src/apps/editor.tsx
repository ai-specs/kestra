// dsh Apps visual editor. One source of truth, two embedding surfaces:
//   - standalone entry (apps-editor.html) at /apps/designer (designer mode) and
//     /apps/{app}/{page}/edit (single-page edit)
//   - SPA-inline mount (src/components/dsh/apps/PagesEditor.vue → mountEditor at /ui/main/pages)
// The editor is decoupled from flow/trigger entirely: every input is a convention path
// (apps/{app}/{page}.json) read/written through the file endpoints.
//
// Styling follows the upstream amis-editor-demo exactly:
//   amis/lib/themes/cxd.css + amis/lib/helper.css + amis/sdk/iconfont.css +
//   amis-editor-core/lib/style.css + fontawesome + themeConfig(cxd) via
//   setDefaultTheme/setThemeConfig. Plain CSS imports (not ?raw) let Vite bundle the font
//   files (iconfont woff2, fontawesome webfonts) referenced by these stylesheets and emit
//   <link> tags — ?raw injection breaks those url() references at runtime (missing icons
//   were exactly the "CSS not loaded" symptom).
import "amis/lib/themes/cxd.css";
import "amis/lib/helper.css";
import "amis/sdk/iconfont.css";
import "amis-editor-core/lib/style.css";
import "@fortawesome/fontawesome-free/css/all.css";
import "@fortawesome/fontawesome-free/css/v4-shims.css";
import {Editor, ShortcutKey} from "amis-editor";
import {setThemeConfig} from "amis-editor-core";
import {setDefaultTheme} from "amis";
import themeConfig from "amis-theme-editor-helper/lib/systemTheme/cxd";
import {createRoot} from "react-dom/client";
import {useEffect, useState} from "react";

setDefaultTheme("cxd");
setThemeConfig(themeConfig);

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

const EDITOR_STYLE_ID = "dsh-apps-editor-style";

// Amis/fontawesome stylesheets are regular Vite CSS imports above (auto-emitted as <link>
// in both the standalone and the SPA-inline bundle). This function only injects the small
// dsh-specific editor shell layout, and is guarded against double injection.
function ensureAmisStyle() {
    if (document.getElementById(EDITOR_STYLE_ID)) {
        return;
    }
    const style = document.createElement("style");
    style.id = EDITOR_STYLE_ID;
    style.textContent = `/* editor shell layout */
.dsh-editor-root { display: flex; min-height: 0; }
.dsh-editor-root.is-embedded { height: 100%; }
.dsh-editor-root:not(.is-embedded) .dsh-editor-shell { height: 100vh; }
.dsh-editor-shell { display: flex; flex-direction: column; flex: 1; min-height: 0; width: 100%; }
.dsh-editor-shell .Editor-inner { flex: 1; overflow: hidden; min-height: 0; }
.dsh-editor-shell .Editor-Demo { height: 100%; display: flex; flex-direction: column; }
.dsh-editor-shell .Editor-Demo .Editor-inner { flex: 1; }
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
`;
    document.head.appendChild(style);
}

interface ApiResult {
    ok: boolean;
    status: number;
    data: unknown;
    msg: string;
}

async function apiRequest(url: string, method: "GET" | "POST" | "PUT" | "DELETE", body?: unknown): Promise<ApiResult> {
    const headers: Record<string, string> = {"Accept": "application/json", "Content-Type": "application/json"};
    const csrf = getCsrfToken();
    if (csrf) {
        headers["X-CSRF-TOKEN"] = csrf;
    }
    try {
        const resp = await fetch(url, {
            method,
            headers,
            body: body !== undefined ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
            credentials: "include",
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

function toast(msg: string) {
    // Minimal inline toast (no amis dependency for the editor shell).
    const el = document.createElement("div");
    el.className = "dsh-toast";
    el.textContent = msg;
    Object.assign(el.style, {
        position: "fixed", top: "16px", left: "50%", transform: "translateX(-50%)",
        background: "#1677ff", color: "#fff", padding: "8px 16px", borderRadius: "4px",
        fontSize: "13px", zIndex: "99999", boxShadow: "0 2px 8px rgba(0,0,0,.15)",
    });
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2500);
}

// ---- single-page editor (also used inside designer mode) ----
function PageEditor({appName, page, embedded}: {appName: string; page: string; embedded?: boolean}) {
    const [schema, setSchema] = useState<unknown>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    // 编辑入口默认编辑模式（组件库 + 属性面板）；预览经头部按钮切换
    const [preview, setPreview] = useState(false);
    const [isMobile, setIsMobile] = useState(false);
    const path = filePath(appName, page);

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
        <div className={`dsh-editor-root ${embedded ? "is-embedded" : ""}`}>
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
                            <a className="header-action-btn exit-btn" href={`/apps/${appName}/${page}`}>
                                预览
                            </a>
                        </div>
                    </div>
                    <div className="Editor-inner">
                        <Editor
                            theme="cxd"
                            preview={preview}
                            isMobile={isMobile}
                            value={schema}
                            onChange={(v: unknown) => setSchema(v)}
                            onSave={save}
                            amisEnv={{
                                fetcher: (api: unknown, data?: unknown) => {
                                    const apiObject = typeof api === "string" ? {url: api, method: "get"} : (api as {url: string; method?: string});
                                    const method = (apiObject.method ?? "get").toUpperCase() as "GET" | "POST" | "PUT" | "DELETE";
                                    return apiRequest(apiObject.url, method, data);
                                },
                                notify: (type: string, msg: string) => {
                                    if (msg) {
                                        console.log(`[amis:${type}] ${msg}`);
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
        <div className={`dsh-editor-root ${embedded ? "is-embedded" : ""}`}>
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

// ---- SPA-inline mount contract (used by PagesEditor.vue) ----
export interface MountEditorOptions {
    mode: "designer" | "page";
    appName?: string;
    page?: string;
    /** true when mounted inside the kestra-ui SPA (height 100% of the content area). */
    embedded?: boolean;
}

export function mountEditor(container: HTMLElement, opts: MountEditorOptions): () => void {
    ensureAmisStyle();
    const root = createRoot(container);
    if (opts.mode === "designer") {
        root.render(<Designer embedded={opts.embedded} />);
    } else {
        root.render(<PageEditor appName={opts.appName ?? "hello"} page={opts.page ?? "index"} embedded={opts.embedded} />);
    }
    return () => root.unmount();
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
    ensureAmisStyle();

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
