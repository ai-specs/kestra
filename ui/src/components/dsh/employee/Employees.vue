<template>
    <TopNavBar :title="routeInfo.title">
        <template #actions>
            <KsButton :icon="RefreshIcon" @click="loadData">{{ t("refresh") }}</KsButton>
        </template>
    </TopNavBar>

    <section v-if="loading" class="full-container p-4">
        <el-skeleton :rows="4" animated />
    </section>

    <section v-else-if="employees.length === 0" class="full-container p-4">
        <span class="text-muted">{{ t("dsh.employee.empty") }}</span>
    </section>

    <section v-else class="full-container p-4">
        <KsTable
            :data="employees"
            :fit="true"
            class="employee-table"
            @row-click="toggleSessions"
        >
            <KsTableColumn prop="sub" :label="t('dsh.employee.user')" :minWidth="180">
                <template #default="{row}">
                    <div class="d-flex align-items-center">
                        <AccountGroupOutline class="me-2" style="font-size: 18px" />
                        <b>{{ row.sub }}</b>
                    </div>
                </template>
            </KsTableColumn>
            <KsTableColumn prop="namespace" label="Namespace" :minWidth="260">
                <template #default="{row}">
                    <code class="ns-code">{{ row.namespace }}</code>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.employee.session_count')" width="110" align="center">
                <template #default="{row}">
                    <span v-if="row.sessionCount > 0" class="session-badge">{{ row.sessionCount }}</span>
                    <span v-else class="text-muted">—</span>
                </template>
            </KsTableColumn>
            <KsTableColumn width="60" align="center">
                <template #default="{row}">
                    <span class="row-hint">{{ expanded === row.namespace ? "▾" : "▸" }}</span>
                </template>
            </KsTableColumn>
        </KsTable>

        <div v-if="expanded" class="expanded-panel mt-3">
            <template v-if="sessions(expanded).length > 0">
                <div class="mb-2 session-title">{{ t("dsh.employee.session_list") }}</div>
                <KsTable :data="sessions(expanded)" size="small" :fit="true">
                    <KsTableColumn prop="sessionId" label="sessionId" :minWidth="260">
                        <template #default="{row}">
                            <code>{{ row.sessionId }}</code>
                        </template>
                    </KsTableColumn>
                    <KsTableColumn :label="t('dsh.employee.reply')" width="70" align="center">
                        <template #default="{row}">
                            <span :class="row.hasReply ? 'ok-mark' : 'text-muted'">{{ row.hasReply ? "✓" : "—" }}</span>
                        </template>
                    </KsTableColumn>
                    <KsTableColumn :label="t('dsh.employee.stderr')" width="70" align="center">
                        <template #default="{row}">
                            <span :class="row.hasStderr ? 'warn-mark' : 'text-muted'">{{ row.hasStderr ? "✓" : "—" }}</span>
                        </template>
                    </KsTableColumn>
                    <KsTableColumn width="200" align="center">
                        <template #default="{row}">
                            <KsButton
                                v-if="row.hasReply"
                                size="small"
                                @click.stop="viewFile(expanded, `/sessions/${row.sessionId}/reply.txt`)"
                            >{{ t("dsh.employee.view_reply") }}</KsButton>
                            <KsButton
                                v-if="row.hasStderr"
                                size="small"
                                @click.stop="viewFile(expanded, `/sessions/${row.sessionId}/stderr.log`)"
                            >{{ t("dsh.employee.view_stderr") }}</KsButton>
                        </template>
                    </KsTableColumn>
                </KsTable>
            </template>
            <div v-else class="no-sessions">
                {{ t("dsh.employee.no_sessions") }}
            </div>
        </div>

        <div v-if="fileViewer" class="file-viewer mt-3">
            <div class="d-flex justify-content-between align-items-center mb-2">
                <code class="file-path">{{ fileViewer.path }}</code>
                <KsButton size="small" @click="fileViewer = null">{{ t("close") }}</KsButton>
            </div>
            <pre class="file-content">{{ fileViewer.content }}</pre>
        </div>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useRoute} from "vue-router"
    import {useI18n} from "vue-i18n"
    import RefreshIcon from "vue-material-design-icons/Refresh.vue"
    import AccountGroupOutline from "vue-material-design-icons/AccountGroupOutline.vue"
    import {KsButton} from "@kestra-io/design-system"

    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"

    const route = useRoute()
    const {t} = useI18n({useScope: "global"})

    const routeInfo = computed(() => ({title: t("dsh.employee.label")}))
    useRouteContext(routeInfo)

    interface EmployeeRow {
        namespace: string
        sub: string
        sessionCount: number
    }
    interface SessionRow {
        sessionId: string
        hasReply: boolean
        hasStderr: boolean
    }

    const loading = ref(false)
    const employees = ref<EmployeeRow[]>([])
    const sessionMap = ref<Record<string, SessionRow[]>>({})
    const expanded = ref<string>("")
    const fileViewer = ref<{path: string, content: string} | null>(null)

    const api = (path: string) => `/api/v1${route.params.tenant ? "/" + route.params.tenant : ""}${path}`

    const loadData = async () => {
        loading.value = true
        fileViewer.value = null
        expanded.value = ""
        try {
            const res = await fetch(api("/dsh-employee/list"), {credentials: "include"})
            if (res.ok) {
                employees.value = await res.json()
            }
        } finally {
            loading.value = false
        }
    }

    const sessions = (namespace: string) => sessionMap.value[namespace] ?? []

    const toggleSessions = async (emp: EmployeeRow) => {
        fileViewer.value = null
        if (expanded.value === emp.namespace) {
            expanded.value = ""
            return
        }
        expanded.value = emp.namespace
        if (!sessionMap.value[emp.namespace]) {
            const res = await fetch(api(`/dsh-employee/${emp.namespace}/sessions`), {credentials: "include"})
            if (res.ok) {
                sessionMap.value = {...sessionMap.value, [emp.namespace]: await res.json()}
            }
        }
    }

    const viewFile = async (namespace: string, path: string) => {
        const res = await fetch(api(`/dsh-employee/${namespace}/file?path=${encodeURIComponent(path)}`), {credentials: "include"})
        if (res.ok) {
            const data = await res.json()
            fileViewer.value = {path: data.path, content: data.content}
        }
    }

    onMounted(loadData)
</script>

<style scoped>
    .ns-code {
        font-size: 0.85em;
        color: var(--ks-content-secondary, #888);
    }
    .session-badge {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 10px;
        font-size: 0.85em;
        background: var(--ks-background-inverted, #eee);
    }
    .text-muted {
        color: var(--ks-content-secondary, #999);
    }
    .expanded-panel {
        border-left: 3px solid var(--ks-border-active, #4f7cff);
        padding-left: 16px;
    }
    .session-title {
        font-weight: 600;
        font-size: 0.9em;
        color: var(--ks-content-secondary, #666);
    }
    .no-sessions {
        color: var(--ks-content-secondary, #999);
        padding: 12px 0;
    }
    .file-viewer {
        border: 1px solid var(--ks-border, #ddd);
        border-radius: 4px;
        padding: 12px;
    }
    .file-path {
        font-size: 0.85em;
        color: var(--ks-content-secondary, #666);
    }
    .file-content {
        white-space: pre-wrap;
        word-break: break-all;
        font-size: 0.85em;
        max-height: 400px;
        overflow: auto;
        margin: 0;
        background: var(--ks-background, #f9f9f9);
        padding: 8px;
        border-radius: 4px;
    }
    .ok-mark { color: var(--ks-color-success, #4caf50); }
    .warn-mark { color: var(--ks-color-warning, #ff9800); }
    .row-hint {
        color: var(--ks-content-secondary, #999);
        font-size: 0.9em;
        cursor: pointer;
    }
</style>
