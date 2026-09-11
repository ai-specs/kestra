<template>
    <!--
        dsh fork: /ui/main/pages —— 页面管理（kestra-ui 外壳内）。
        左侧 = kestra-ui 外壳直接渲染的"应用 → 页面文件"树（不在 iframe 内），
        右侧 = 选中页面的真实预览（iframe 加载渲染页）。

        管理/交付的载体 = nsfile 下的 apps/**/*.json 文件：
          · index.json = 首页（约定名，app 型页面）；
          · 其余 *.json = 独立页面文件。
        若某页面文件的名字命中首页（app 型 index.json）pages 中某个子页面的
        url（如 schema.json ↔ url "/schema"），则它是"嵌入首页"的子页面文件——
        真实地址是首页 + hash（/apps/{app}/index#/{url}），不是独立渲染页，
        但编辑仍打开它自己的文件（/apps/{app}/{page}/edit）。
        其余独立页面保持 /apps/{app}/{page} 渲染。
    -->
    <div class="dsh-pages">
        <aside class="dsh-pages-tree">
            <h3>应用</h3>
            <div v-if="error" class="dsh-tree-warning">加载失败：{{ error }}</div>
            <div v-else-if="rows.length === 0" class="dsh-tree-empty">
                约定目录 apps/ 下暂无页面。<br/>
                在 AppList 页进入某页面的「设计」入口后首次保存会自动创建文件。
            </div>
            <template v-for="row in rows" :key="row.key">
                <div v-if="row.kind === 'app'" class="dsh-tree-node is-app">📁 {{ row.appName }}</div>
                <div v-else-if="row.kind === 'warning'" class="dsh-tree-warning">{{ row.text }}</div>
                <div
                    v-else
                    class="dsh-tree-row"
                    :class="{ 'is-group': row.kind === 'group' }"
                    :style="{ paddingLeft: (8 + row.depth * 20) + 'px' }"
                >
                    <div v-if="row.kind === 'group'" class="dsh-tree-node is-group">{{ row.label }}/</div>
                    <button
                        v-else
                        class="dsh-tree-node is-page"
                        :class="{ 'is-active': selected && selected.key === row.key }"
                        @click="select(row)"
                    >
                        {{ row.label }}
                        <span v-if="row.isIndex" class="dsh-tree-index">首页</span>
                        <span v-if="row.embedded" class="dsh-tree-embedded">嵌入</span>
                    </button>
                    <a
                        v-if="row.kind !== 'group'"
                        class="dsh-page-edit"
                        :href="row.editUrl"
                        :title="`编辑 ${row.label}`"
                    >
                        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
                            <path fill="currentColor" d="M20.71,4.04C21.1,3.65 21.1,3 20.71,2.63L18.37,0.29C18,-0.1 17.35,-0.1 16.96,0.29L15,2.25L18.75,6L20.71,4.04M14.25,4.5L3,15.75V19.5H6.75L18,8.25L14.25,4.5Z"/>
                        </svg>
                    </a>
                </div>
            </template>
        </aside>
        <div class="dsh-pages-preview">
            <iframe
                v-if="preview"
                :key="preview.path"
                class="dsh-pages-preview-frame"
                :src="preview.url"
                :title="preview.label"
            />
            <div v-else class="dsh-pages-placeholder">从左侧选择一个页面查看预览</div>
        </div>
    </div>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"

    interface TreeNode {
        name: string
        kind: "page" | "group"
        index?: boolean
        children?: TreeNode[]
    }

    interface AppNode {
        appName: string
        namespace?: string | null
        warning?: string | null
        pages: TreeNode[]
    }

    interface Row {
        key: string
        kind: "app" | "warning" | "group" | "page"
        appName: string
        label: string
        depth: number
        isIndex?: boolean
        /** 该文件是"嵌入首页"的子页面文件（命中首页 pages 的 url） */
        embedded?: boolean
        text?: string
        previewUrl?: string
        editUrl?: string
    }

    const apps = ref<AppNode[]>([])
    const error = ref<string | null>(null)
    // appName -> 首页（app 型 index.json）pages 中出现的子页面 url 集合（含 "/"）
    const homeChildUrls = ref<Record<string, Set<string>>>({})
    const selected = ref<Row | null>(null)

    const preview = computed(() => {
        if (!selected.value?.previewUrl) {
            return null
        }
        const row = selected.value
        return {
            path: `${row.appName}/${row.key}`,
            url: row.previewUrl,
            label: row.label,
        }
    })

    function collectUrls(nodes: unknown[] | undefined, out: Set<string>): void {
        if (!nodes) {
            return
        }
        for (const n of nodes) {
            const node = n as {url?: string; children?: unknown[]}
            if (node.url) {
                out.add(node.url)
            }
            if (node.children) {
                collectUrls(node.children, out)
            }
        }
    }

    /** 读首页 index.json；若为 app 型，收集其子页面 url（用于判定"嵌入首页"的文件） */
    function loadHomeChildUrls(appName: string): Promise<void> {
        return fetch(`/api/v1/apps/files?path=${encodeURIComponent(`apps/${appName}/index.json`)}`, {
            headers: {"Accept": "application/json"},
            credentials: "include",
        })
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
            .then((schema: unknown) => {
                const s = schema as {type?: string; pages?: unknown[]} | null
                if (s?.type !== "app" || !Array.isArray(s.pages)) {
                    return
                }
                const urls = new Set<string>()
                collectUrls(s.pages, urls)
                homeChildUrls.value[appName] = urls
            })
            .catch(() => {
                // 首页缺失/非 app 型：全部按独立页面处理
            })
    }

    /** 文件名是否命中首页某个子页面 url（schema.json ↔ url "/schema"） */
    function embeddedFor(appName: string, fileName: string): string | null {
        const urls = homeChildUrls.value[appName]
        if (!urls) {
            return null
        }
        const matched = [...urls].find(u => u.replace(/^\/+/, "") === fileName)
        if (!matched) {
            return null
        }
        return matched
    }

    function pushFilePages(rows: Row[], appName: string, nodes: TreeNode[], depth: number): void {
        for (const n of nodes) {
            if (n.kind === "group") {
                rows.push({key: `${appName}/g:${n.name}`, kind: "group", appName, label: n.name, depth})
                pushFilePages(rows, appName, n.children ?? [], depth + 1)
                continue
            }
            // 首页：约定名 index.json
            if (n.index || n.name === "index") {
                const base = `/apps/${appName}/index`
                rows.push({
                    key: `${appName}/index`,
                    kind: "page",
                    appName,
                    label: n.name,
                    depth,
                    isIndex: true,
                    previewUrl: base,
                    editUrl: `${base}/edit`,
                })
                continue
            }
            // 其他页面文件：若命中首页子页面 url → "嵌入首页"，真实地址 = 首页 + hash
            const hit = embeddedFor(appName, n.name)
            if (hit) {
                const base = `/apps/${appName}/index`
                const hash = hit === "/" ? "#/" : `#${hit.startsWith("/") ? hit : `/${hit}`}`
                rows.push({
                    key: `${appName}/p:${n.name}`,
                    kind: "page",
                    appName,
                    label: n.name,
                    depth,
                    embedded: true,
                    previewUrl: base + hash,
                    editUrl: `/apps/${appName}/${n.name}/edit`,
                })
                continue
            }
            const base = `/apps/${appName}/${n.name}`
            rows.push({
                key: `${appName}/p:${n.name}`,
                kind: "page",
                appName,
                label: n.name,
                depth,
                previewUrl: base,
                editUrl: `${base}/edit`,
            })
        }
    }

    const rows = computed<Row[]>(() => {
        const out: Row[] = []
        for (const app of apps.value) {
            out.push({key: `app:${app.appName}`, kind: "app", appName: app.appName, label: app.appName, depth: 0})
            if (app.warning) {
                out.push({key: `warn:${app.appName}`, kind: "warning", appName: app.appName, label: "", depth: 1, text: app.warning})
            }
            pushFilePages(out, app.appName, app.pages ?? [], 1)
        }
        return out
    })

    function select(row: Row) {
        if (row.kind === "group" || row.kind === "app" || row.kind === "warning") {
            return
        }
        selected.value = row
    }

    onMounted(async () => {
        try {
            const resp = await fetch("/api/v1/apps/pages", {
                headers: {"Accept": "application/json"},
                credentials: "include",
            })
            if (resp.status === 401) {
                window.location.assign("/oidc/login?from=" + encodeURIComponent(window.location.pathname))
                return
            }
            if (!resp.ok) {
                throw new Error(`HTTP ${resp.status}`)
            }
            apps.value = (await resp.json()) as AppNode[]
            await Promise.all(apps.value.map(a => loadHomeChildUrls(a.appName)))
            const first = rows.value.find(r => r.kind === "page")
            if (first) {
                select(first)
            }
        } catch (e) {
            error.value = (e as Error).message ?? "加载失败"
        }
    })
</script>

<style scoped>
    .dsh-pages {
        display: flex;
        height: 100%;
        min-height: 0;
        width: 100%;
    }
    .dsh-pages-tree {
        width: 280px;
        min-width: 280px;
        border-right: 1px solid var(--ks-border-color, #e8e8e8);
        overflow-y: auto;
        padding: 12px 0;
        background: var(--ks-surface, #fff);
    }
    .dsh-pages-tree h3 {
        font-size: 13px;
        color: var(--ks-text-color-2, #666);
        padding: 0 16px;
        margin: 8px 0 4px;
        font-weight: 600;
    }
    .dsh-tree-row {
        display: flex;
        align-items: center;
        padding-right: 8px;
        position: relative;
    }
    .dsh-tree-node {
        display: block;
        flex: 1;
        text-align: left;
        border: none;
        background: none;
        padding: 5px 12px 5px 0;
        font-size: 13px;
        color: var(--ks-text-color, #333);
        cursor: pointer;
        min-width: 0;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }
    .dsh-tree-node:hover {
        background: var(--ks-background-hover, #f2f3f7);
    }
    .dsh-tree-node.is-app {
        font-weight: 600;
        padding-top: 8px;
    }
    .dsh-tree-node.is-group {
        color: var(--ks-text-color-2, #888);
        cursor: default;
    }
    .dsh-tree-node.is-active {
        background: var(--ks-background-active, #e8f1ff);
        color: var(--ks-primary, #1677ff);
    }
    .dsh-tree-node .dsh-tree-index {
        color: var(--ks-primary, #1677ff);
        font-size: 11px;
        border: 1px solid var(--ks-primary, #1677ff);
        border-radius: 2px;
        padding: 0 3px;
        margin-left: 6px;
    }
    .dsh-tree-node .dsh-tree-embedded {
        color: #8c8c8c;
        font-size: 11px;
        border: 1px dashed #bfbfbf;
        border-radius: 2px;
        padding: 0 3px;
        margin-left: 6px;
    }
    .dsh-page-edit {
        flex-shrink: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 22px;
        height: 22px;
        border-radius: 4px;
        color: var(--ks-text-color-2, #999);
        opacity: 0;
        transition: opacity .15s;
    }
    .dsh-tree-row:hover .dsh-page-edit,
    .dsh-page-edit:hover {
        opacity: 1;
        color: var(--ks-primary, #1677ff);
        background: var(--ks-background-hover, #f2f3f7);
    }
    .dsh-tree-empty {
        padding: 24px 16px;
        color: var(--ks-text-color-2, #999);
        font-size: 13px;
        line-height: 1.8;
    }
    .dsh-tree-warning {
        margin: 4px 12px;
        padding: 6px 8px;
        background: #fff7e6;
        border: 1px solid #ffd591;
        border-radius: 4px;
        color: #d46b08;
        font-size: 12px;
    }
    .dsh-pages-preview {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
    }
    .dsh-pages-preview-frame {
        flex: 1;
        border: 0;
        width: 100%;
        background: #fff;
    }
    .dsh-pages-placeholder {
        flex: 1;
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--ks-text-color-2, #999);
        font-size: 14px;
    }
</style>
