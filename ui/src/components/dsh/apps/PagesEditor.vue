<template>
    <!--
        dsh fork: /ui/main/pages —— 页面管理（kestra-ui 外壳内）。
        布局：
          左侧 = kestra-ui 外壳直接渲染的"应用 → 一级菜单 → 子菜单"树（不在 iframe 内），
          右侧 = 选中页面的真实预览（iframe 加载 /apps/{app}/{page} 渲染页）。
        每个页面行右侧的编辑图标 → 当前窗体整页跳转全屏 amis-editor
        （/apps/{app}/{page}/edit，无 kestra-ui 外壳），编辑器最右侧的"退出"返回本页。
    -->
    <div class="dsh-pages">
        <aside class="dsh-pages-tree">
            <h3>应用</h3>
            <div v-if="error" class="dsh-tree-warning">加载失败：{{ error }}</div>
            <div v-else-if="apps.length === 0" class="dsh-tree-empty">
                约定目录 apps/ 下暂无页面。<br/>
                在 AppList 页进入某页面的「设计」入口后首次保存会自动创建文件。
            </div>
            <template v-for="app in apps" :key="app.appName">
                <div class="dsh-tree-node is-app">📁 {{ app.appName }}</div>
                <div v-if="app.warning" class="dsh-tree-warning">{{ app.warning }}</div>
                <div
                    v-for="p in flatten(app.pages, app.appName)"
                    :key="`${app.appName}/${p.name}`"
                    class="dsh-tree-row"
                    :class="{ 'is-group': p.kind === 'group', 'is-page': p.kind === 'page' }"
                    :style="{ paddingLeft: (8 + p.depth * 20) + 'px' }"
                >
                    <button
                        v-if="p.kind === 'page'"
                        class="dsh-tree-node is-page"
                        :class="{ 'is-active': selected && selected.appName === app.appName && selected.name === p.name }"
                        @click="select(app.appName, p.name)"
                    >
                        {{ p.name }}
                        <span v-if="p.index" class="dsh-tree-index">首页</span>
                    </button>
                    <div v-else class="dsh-tree-node is-group">{{ p.name }}/</div>
                    <a
                        v-if="p.kind === 'page'"
                        class="dsh-page-edit"
                        :href="`/apps/${app.appName}/${p.name}/edit`"
                        :title="`编辑 ${p.name}`"
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

    interface FlatNode {
        appName: string
        name: string
        kind: "page" | "group"
        depth: number
        index?: boolean
    }

    const apps = ref<AppNode[]>([])
    const error = ref<string | null>(null)
    const selected = ref<{appName: string; name: string} | null>(null)

    const preview = computed(() => {
        if (!selected.value) {
            return null
        }
        const {appName, name} = selected.value
        return {
            path: `apps/${appName}/${name}`,
            url: `/apps/${appName}/${name}`,
            label: `${appName}/${name}`,
        }
    })

    function flatten(nodes: TreeNode[] | undefined, appName: string, depth = 1): FlatNode[] {
        const out: FlatNode[] = []
        if (!nodes) {
            return out
        }
        for (const n of nodes) {
            if (n.kind === "page") {
                out.push({appName, name: n.name, kind: "page", depth, index: n.index})
            } else {
                out.push({appName, name: n.name, kind: "group", depth})
                out.push(...flatten(n.children, appName, depth + 1))
            }
        }
        return out
    }

    function select(appName: string, name: string) {
        selected.value = {appName, name}
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
            // 默认选中第一个 app 的第一个页面
            const firstApp = apps.value[0]
            if (firstApp) {
                const first = flatten(firstApp.pages, firstApp.appName).find(p => p.kind === "page")
                if (first) {
                    selected.value = {appName: first.appName, name: first.name}
                }
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
