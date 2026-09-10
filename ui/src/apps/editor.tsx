// Standalone dsh Apps visual editor entry (served at /apps/designer and
// /apps/{app}/{page}/edit, outside the Kestra /ui/ SPA). Dual mode, one bundle:
//   - /apps/designer             → designer mode: left page tree (GET /api/v1/apps/pages)
//                                  + amis-editor in the main area
//   - /apps/{app}/{page}/edit    → single-page edit mode (page defaults to "index";
//                                  {page} may be a group path "x/y" for third-level pages)
// The editor is decoupled from flow/trigger entirely: every input is a convention path
// (apps/{app}/{page}.json) read/written through the file endpoints.
//
// Design: docs/dsh-apps-amis-editor.md §6.4. amis-editor@6.13.0 ships no standalone
// .css (verified tarball) — its styling reuses the amis/amis-ui theme css injected at
// runtime exactly like src/apps/main.ts.
import {Editor, ShortcutKey} from "amis-editor";
import {createRoot} from "react-dom/client";
import {useEffect, useState} from "react";
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
    if (document.getElementById("dsh-apps-editor-style")) {
        return;
    }
    const style = document.createElement("style");
    style.id = "dsh-apps-editor-style";
    style.textContent = `${amisCss}
/* editor shell layout */
.dsh-editor-shell { display: flex; flex-direction: column; height: 100vh; }
.dsh-editor-shell .Editor-inner { flex: 1; overflow: hidden; }
.dsh-editor-shell .Editor-Demo { height: 100%; }
.dsh-designer { display: flex; height: 100vh; overflow: hidden; }
.dsh-designer-tree { width: 260px; min-width: 260px; background: #fff; border-right: 1px solid #e8e8e8; overflow-y: auto; padding: 12px 0; }
.dsh-designer-tree h3 { font-size: 13px; color: #666; padding: 0 16px; margin: 8px 0 4px; }
.dsh-designer-main { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
.dsh-designer-main > .Editor-Demo { flex: 1; min-height: 0; }
.dsh-tree-node { display: block; width: 100%; text-align: left; border: none; background: none; padding: 6px 16px; font-size: 13px; color: #333; cursor: pointer; }
.dsh-tree-node:hover { background: #f2f3f7; }
.dsh-tree-node.is-active { background: #e8f1ff; color: #1677ff; }
.dsh-tree-node.is-app { font-weight: 600; }
.dsh-tree-node.is-page { padding-left: 36px; }
.dsh-tree-node.is-page3 { padding-left: 56px; }
.dsh-tree-node .dsh-tree-index { color: #1677ff; font-size: 11px; border: 1px solid #1677ff; border-radius: 2px; padding: 0 3px; margin-left: 6px; }
.dsh-tree-empty { padding: 24px 16px; color: #999; font-size: 13px; line-height: 1.8; }
.dsh-tree-warning { margin: 4px 12px; padding: 6px 8px; background: #fff7e6; border: 1px solid #ffd591; border-radius: 4px; color: #d46b08; font-size: 12px; }
.dsh-designer-placeholder { flex: 1; display: flex; align-items: center; justify-content: center; color: #999; font-size: 14px; }
`;
    document.head.appendChild(style);
}

interface ApiResult {
    ok: boolean;
    status: number;
    data: unknown;
    msg: string;
}

async function apiRequest(url: string, method: "GET" | "PUT", body?: unknown): Promise<ApiResult> {
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
function PageEditor({appName, page}: {appName: string; page: string}) {
    const [schema, setSchema] = useState<unknown>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [preview, setPreview] = useState(true);
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
                            return apiRequest(apiObject.url, (apiObject.method ?? "get").toUpperCase() === "POST" ? "PUT" : "GET", data);
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
                    }}
                />
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

function Designer() {
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
                    <PageEditor appName={selected.appName} page={selected.page} />
                ) : (
                    <div className="dsh-designer-placeholder">选择左侧一个页面开始编辑</div>
                )}
            </div>
        </div>
    );
}

// ---- boot: URL → mode ----
function boot() {
    if (!isLoggedIn()) {
        redirectToLogin();
        return;
    }
    ensureAmisStyle();

    const path = window.location.pathname;
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
