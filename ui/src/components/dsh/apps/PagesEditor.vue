<template>
    <!--
        dsh fork: /ui/main/pages —— 页面管理（kestra-ui 外壳内）。
        布局：
          左侧 = kestra-ui 外壳直接渲染的"应用 → 一级菜单 → 子菜单"树（不在 iframe 内），
          右侧 = 选中页面的真实预览（iframe 加载渲染页）。
        树规则：
          · app 型首页（index.json, type:"app"）会展开其内嵌子页面（pages 递归）作为
            子菜单，子页面的真实地址是首页 + hash（/apps/{app}/index#/{url}），
            不是独立渲染页；
          · 磁盘上与该子页面 url 同名的独立文件（如 schema.json ↔ url="/schema"）
            视为被首页子页面吸收，不重复显示；
          · 其余独立页面保持独立渲染地址 /apps/{app}/{page}。
        每个页面行右侧的编辑图标 → 当前窗体整页跳转全屏 amis-editor
        （子页面/首页编辑的是 index.json：/apps/{app}/index/edit），编辑器最右侧"退出"返回本页。
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
                <div
                    v-if="row.kind === 'app'"
                    class="dsh-tree-node is-app"
                >📁 {{ row.appName }}</div>
                <div v-else-if="row.kind === 'warning'" class="dsh-tree-warning">{{ row.text }}</div>
                <div
                    v-else
                    class="dsh-tree-row"
                    :class="{ 'is-group': row.kind === 'group', 'is-page': row.kind !== 'group' }"
                    :style="{ paddingLeft: (8 + row.depth * 20) + 'px' }"
                >
                    <button
                        v-if="row.kind !== 'group'"
                        class="dsh-tree-node is-page"
                        :class="{ 'is-active': selected && selected.key === row.key }"
                        @click="select(row)"
                    >
                        {{ row.label }}
                        <span v-if="row.isIndex" class="dsh-tree-index">首页</span>
                    </button>
                    <div v-else class="dsh-tree-node is-group">{{ row.label }}/</div>
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

    interface SubPage {
        label: string
        url: string
    }

    interface Row {
        key: string
        kind: "app" | "warning" | "group" | "index" | "sub" | "page"
        appName: string
        label: string
        depth: number
        isIndex?: boolean
        text?: string
        /** 预览 iframe 地址 */
        previewUrl?: string
        /** 全屏编辑器地址 */
        editUrl?: string
    }

    const apps = ref<AppNode[]>([])
    const error = ref<string | null>(null)
    // appName -> 首页（app 型）解析出的子页面（url 为 hash 路径，如 "/submit"）
    const subPages = ref<Record<string, SubPage[]>>({})
    // 已被首页子页面吸收的文件名集合：如 schema.json ↔ url="/schema" → "hello/schema"
    const absorbed = ref<Set<string>>(new Set())
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

    function walkSubPages(nodes: unknown[] | undefined, out: SubPage[]): void {
        if (!nodes) {
            return
        }
        for (const n of nodes) {
            const node = n as {label?: string; url?: string; children?: unknown[]}
            if (node.url) {
                out.push({label: node.label ?? node.url, url: node.url})
            }
            if (node.children) {
                walkSubPages(node.children, out)
            }
        }
    }

    function loadIndexSubPages(appName: string): Promise<void> {
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
                const out: SubPage[] = []
                walkSubPages(s.pages, out)
                subPages.value[appName] = out
                for (const p of out) {
                    // "/schema" ↔ 文件 schema.json
                    const fileName = p.url.replace(/^\/+/, "")
                    if (fileName) {
                        absorbed.value.add(`${appName}/${fileName}`)
                    }
                }
            })
            .catch(() => {
                // 首页非 app 型或读取失败：按普通文件树展示
            })
    }

    function buildRows(): Row[] {
        const rows: Row[] = []
        for (const app of apps.value) {
            rows.push({key: `app:${app.appName}`, kind: "app", appName: app.appName, label: app.appName, depth: 0})
            if (app.warning) {
                rows.push({key: `warn:${app.appName}`, kind: "warning", appName: app.appName, label: "", depth: 1, text: app.warning})
            }
            const subs = subPages.value[app.appName] ?? []
            let renderedIndex = false
            for (const node of app.pages ?? []) {
                if (node.kind === "group") {
                    rows.push({key: `${app.appName}/g:${node.name}`, kind: "group", appName: app.appName, label: node.name, depth: 1})
                    pushFilePages(rows, app.appName, node.children ?? [], 2)
                    continue
                }
                // 首页：展开其子页面（如有）
                if (node.index || node.name === "index") {
                    renderedIndex = true
                    const base = `/apps/${app.appName}/index`
                    rows.push({
                        key: `${app.appName}/index`,
                        kind: "index",
                        appName: app.appName,
                        label: "index",
                        depth: 1,
                        isIndex: true,
                        previewUrl: base,
                        editUrl: `${base}/edit`,
                    })
                    for (const sub of subs) {
                        const hash = sub.url === "/" ? "#/" : `#${sub.url.startsWith("/") ? sub.url : `/${sub.url}`}`
                        rows.push({
                            key: `${app.appName}/sub:${sub.url}`,
                            kind: "sub",
                            appName: app.appName,
                            label: sub.label,
                            depth: 2,
                            previewUrl: base + hash,
                            editUrl: `${base}/edit`,
                        })
                    }
                    continue
                }
                // 独立页面：已被首页子页面吸收的不再显示
                if (absorbed.value.has(`${app.appName}/${node.name}`)) {
                    continue
                }
                pushFilePages(rows, app.appName, [node], 1)
            }
            // 首页不存在但树里有子页面？（防御：不渲染）
            void renderedIndex
        }
        return rows
    }

    function pushFilePages(rows: Row[], appName: string, nodes: TreeNode[], depth: number): void {
        for (const n of nodes) {
            if (absorbed.value.has(`${appName}/${n.name}`)) {
                continue
            }
            if (n.kind === "group") {
                rows.push({key: `${appName}/g:${n.name}`, kind: "group", appName, label: n.name, depth})
                pushFilePages(rows, appName, n.children ?? [], depth + 1)
            } else {
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
    }

    const rows = computed<Row[]>(buildRows)

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
            // 解析每个 app 的首页子页面（并行）
            await Promise.all(apps.value.map(a => loadIndexSubPages(a.appName)))
            // 默认选中第一个可预览行
            const first = rows.value.find(r => r.kind === "index" || r.kind === "sub" || r.kind === "page")
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
