<template>
    <div class="employee-files">
        <div v-if="loading" class="p-4">
            <KsSkeleton :rows="6" animated />
        </div>

        <div v-else-if="rows.length === 0" class="p-4">
            <span class="text-muted">{{ t("dsh.employee.no_files") }}</span>
        </div>

        <div v-else class="file-splitter plain">
            <div class="tree-side">
                <div class="tree-pane">
                    <KsInput
                        v-model="filter"
                        size="small"
                        clearable
                        :placeholder="t('dsh.employee.filter_files')"
                        class="tree-filter"
                    />
                    <KsTree
                        ref="treeRef"
                        :data="visibleNodes"
                        nodeKey="path"
                        :default-expanded-keys="defaultExpanded"
                        :default-expand-all="!!filter"
                        highlight-current
                        expand-on-click-node
                        @node-click="onNodeClick"
                    >
                        <template #default="{data}">
                            <span class="tree-node">
                                <FolderOutline v-if="data.directory" class="tree-icon folder" />
                                <FileDocumentOutline v-else class="tree-icon" />
                                <span class="tree-label" :title="data.path">{{ data.label }}</span>
                            </span>
                        </template>
                    </KsTree>
                </div>
            </div>
            <div class="preview-side">
                <div class="preview-pane">
                    <template v-if="preview">
                        <div class="preview-header">
                            <code class="file-path">{{ preview.path }}</code>
                            <span class="text-muted file-meta">{{ humanFileSize(preview.size) }}</span>
                            <span v-if="preview.truncated" class="truncated-badge">
                                {{ t("dsh.employee.truncated") }}
                            </span>
                        </div>
                        <div v-if="preview.binary" class="binary-hint">
                            {{ t("dsh.employee.binary_hint") }}
                        </div>
                        <pre v-else class="file-content">{{ preview.content }}</pre>
                    </template>
                    <div v-else class="no-selection">
                        <FileDocumentOutline :size="28" />
                        <span>{{ t("dsh.employee.no_selection") }}</span>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
    import {computed, nextTick, onMounted, ref, watch} from "vue"
    import {useRoute} from "vue-router"
    import {useI18n} from "vue-i18n"
    import FolderOutline from "vue-material-design-icons/FolderOutline.vue"
    import FileDocumentOutline from "vue-material-design-icons/FileDocumentOutline.vue"
    import {KsInput, KsSkeleton} from "@kestra-io/design-system"
    import {humanFileSize} from "../../../utils/utils"

    interface TreeRow {
        path: string
        size: number
        directory: boolean
    }
    interface TreeNode {
        path: string
        size: number
        directory: boolean
        label: string
        children?: TreeNode[]
    }

    const props = defineProps<{
        namespace: string
    }>()

    const route = useRoute()
    const {t} = useI18n({useScope: "global"})

    const loading = ref(false)
    const rows = ref<TreeRow[]>([])
    const filter = ref("")
    const preview = ref<{path: string, size: number, truncated: boolean, binary: boolean, content: string} | null>(null)

    const api = (path: string) => `/api/v1${route.params.tenant ? "/" + route.params.tenant : ""}${path}`

    // 平铺路径 → 嵌套树（目录优先、同级按名排序；文件节点不带 children 即叶子）；sessions/ 一级默认展开
    const nodes = computed<TreeNode[]>(() => {
        const root: TreeNode = {path: "", size: 0, directory: true, label: "", children: []}
        const index = new Map<string, TreeNode>([["", root]])
        for (const row of rows.value) {
            // 物理列举不过滤约定结构：意外目录/畸形文件同样显示；仅跳过无意义的空路径
            if (!row.path) {
                continue
            }
            const segments = row.path.split("/")
            const label = segments[segments.length - 1]
            const parentPath = segments.slice(0, -1).join("/")
            const node: TreeNode = row.directory
                ? {...row, label, children: []}
                : {...row, label}
            index.set(row.path, node)
            // .vN 版本副本（reply.txt.v2）物理上是同名文件的兄弟对象，路径上却形似其
            // 子节点——向上归位到最近的目录祖先，保持与 docker ls 一致的平铺视图
            let parent = index.get(parentPath) ?? root
            while (!parent.children) {
                const parentSegs = parent.path.split("/")
                parent = parentSegs.length <= 1 ? root : (index.get(parentSegs.slice(0, -1).join("/")) ?? root)
            }
            parent.children.push(node)
        }
        const sortTree = (node: TreeNode) => {
            node.children?.sort((a, b) =>
                a.directory === b.directory ? a.label.localeCompare(b.label) : (a.directory ? -1 : 1))
            node.children?.forEach(sortTree)
        }
        sortTree(root)
        return root.children ?? []
    })

    // 客户端过滤：保留命中节点及其祖先链（filter 非空时全展开）
    const filterTree = (items: TreeNode[], query: string): TreeNode[] => {
        const kept: TreeNode[] = []
        for (const item of items) {
            const hit = item.path.toLowerCase().includes(query)
            const keptChildren = item.children ? filterTree(item.children, query) : []
            if (hit || keptChildren.length > 0) {
                kept.push(hit ? item : {...item, children: keptChildren})
            }
        }
        return kept
    }

    const visibleNodes = computed(() => {
        const query = filter.value.trim().toLowerCase()
        return query ? filterTree(nodes.value, query) : nodes.value
    })

    // 过滤激活时展开全部命中目录（default-expand-all 不响应数据更新，改为收集目录 key）
    const collectDirPaths = (items: TreeNode[], acc: string[] = []): string[] => {
        for (const item of items) {
            if (item.directory) {
                acc.push(item.path)
                if (item.children) {
                    collectDirPaths(item.children, acc)
                }
            }
        }
        return acc
    }

    /** 深链目标路径（?path=，归一去首尾斜杠）；非法/不存在时各环节静默降级。 */
    const targetPath = computed(() => {
        const raw = route.query.path
        if (typeof raw !== "string" || !raw.trim()) {
            return ""
        }
        return raw.replace(/^\/+|\/+$/g, "")
    })

    const defaultExpanded = computed(() => {
        const query = filter.value.trim()
        if (query) {
            return collectDirPaths(visibleNodes.value)
        }
        // 深链（?path=/sessions/{id}）：展开目标目录的全部祖先 + 目标自身并高亮
        if (targetPath.value) {
            const segments = targetPath.value.split("/").filter(Boolean)
            if (segments.length > 0) {
                return segments.map((_, i) => segments.slice(0, i + 1).join("/"))
            }
        }
        return rows.value.some(r => r.path === "sessions") ? ["sessions"] : []
    })

    const treeRef = ref<{setCurrentKey: (key: unknown) => void}>()

    /** 数据就绪后高亮深链目标节点并滚入视野（best-effort：不存在则跳过）。 */
    const focusTarget = async () => {
        if (!targetPath.value || loading.value) {
            return
        }
        await nextTick()
        try {
            treeRef.value?.setCurrentKey(targetPath.value)
        } catch {
            // 目标节点不存在（路径已删/拼错）——树保持默认展开即可
        }
        setTimeout(() => {
            try {
                document.querySelector(".employee-files .kel-tree-node.is-current")
                    ?.scrollIntoView({block: "center"})
            } catch {
                // 忽略滚动失败
            }
        }, 150)
    }

    watch([() => route.query.path, loading], ([, isLoading]) => {
        if (!isLoading) {
            focusTarget()
        }
    })

    const loadTree = async () => {
        loading.value = true
        preview.value = null
        try {
            const res = await fetch(api(`/dsh-employee/${encodeURIComponent(props.namespace)}/tree`), {credentials: "include"})
            if (res.ok) {
                rows.value = await res.json()
            }
        } finally {
            loading.value = false
        }
    }

    const onNodeClick = (node: TreeNode) => {
        if (node.directory) {
            return
        }
        loadPreview(node.path)
    }

    const loadPreview = async (path: string) => {
        const res = await fetch(api(`/dsh-employee/${encodeURIComponent(props.namespace)}/file?path=${encodeURIComponent("/" + path)}`), {credentials: "include"})
        if (res.ok) {
            preview.value = await res.json()
        }
    }

    watch(() => props.namespace, loadTree)
    onMounted(loadTree)
</script>

<style lang="scss" scoped>
    .employee-files {
        height: 100%;
        display: flex;
        flex-direction: column;
    }
    .file-splitter.plain {
        margin: 1rem;
        border: 1px solid var(--ks-border-default, #ddd);
        border-radius: var(--ks-radius-lg, 8px);
        flex: 1;
        min-height: 0;
        overflow: hidden;
        display: flex;
        flex-direction: row;
        align-items: stretch;
    }
    .tree-side {
        width: 30%;
        min-width: 220px;
        overflow: hidden;
        display: flex;
        flex-direction: column;
        border-right: 1px solid var(--ks-border-default, #eee);
    }
    .preview-side {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
    }
    .tree-pane {
        height: 100%;
        display: flex;
        flex-direction: column;
        overflow: auto;
        padding: 0.5rem;
    }
    .tree-filter {
        margin-bottom: 0.5rem;
    }
    .tree-node {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        overflow: hidden;

        .tree-icon {
            flex-shrink: 0;
            color: var(--ks-icon-muted, #999);

            &.folder {
                color: var(--ks-icon-primary, #4f7cff);
            }
        }
        .tree-label {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
    }
    .preview-pane {
        height: 100%;
        display: flex;
        flex-direction: column;
        overflow: hidden;
    }
    .preview-header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 0.5rem 0.75rem;
        border-bottom: 1px solid var(--ks-border-default, #eee);
        flex-wrap: wrap;
    }
    .file-path {
        font-size: 0.85em;
        color: var(--ks-content-secondary, #666);
    }
    .file-meta {
        font-size: 0.8em;
    }
    .truncated-badge {
        font-size: 0.75em;
        padding: 1px 8px;
        border-radius: 8px;
        background: var(--ks-background-inverted, #eee);
        color: var(--ks-content-secondary, #666);
    }
    .file-content {
        flex: 1;
        margin: 0;
        padding: 0.75rem;
        overflow: auto;
        white-space: pre-wrap;
        word-break: break-all;
        font-size: 0.85em;
    }
    .binary-hint {
        padding: 1rem;
        color: var(--ks-content-secondary, #999);
    }
    .no-selection {
        height: 100%;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 8px;
        color: var(--ks-icon-muted, #999);
    }
    .text-muted {
        color: var(--ks-content-secondary, #999);
    }
</style>
