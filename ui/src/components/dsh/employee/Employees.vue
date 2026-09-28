<template>
    <Navbar :title="routeInfo.title">
        <template #actions>
            <el-button @click="loadData" :icon="Refresh">{{ $t("refresh") }}</el-button>
        </template>
    </Navbar>

    <KsRow class="row-padding">
        <KsCol v-if="loading" class="p-3"><el-skeleton :rows="3" animated /></KsCol>

        <KsCol v-else-if="employees.length === 0" class="p-3">
            <el-empty :description="$t('dsh.employee.empty')" />
        </KsCol>

        <KsCol v-else :span="24">
            <el-table :data="employees" stripe border>
                <el-table-column prop="sub" :label="$t('dsh.employee.user')" min-width="200">
                    <template #default="{row}">
                        <div class="d-flex align-items-center">
                            <AccountGroupOutline class="me-2 icon" />
                            <span>{{ row.sub }}</span>
                        </div>
                    </template>
                </el-table-column>
                <el-table-column prop="namespace" label="Namespace" min-width="280">
                    <template #default="{row}">
                        <code class="employee-ns">{{ row.namespace }}</code>
                    </template>
                </el-table-column>
                <el-table-column :label="$t('dsh.employee.session_count')" width="120" align="center">
                    <template #default="{row}">
                        <el-tag v-if="row.sessionCount > 0" size="small" type="info">{{ row.sessionCount }}</el-tag>
                        <span v-else class="text-muted">—</span>
                    </template>
                </el-table-column>
                <el-table-column :label="$t('actions')" width="120" align="center">
                    <template #default="{row}">
                        <el-button
                            v-if="row.sessionCount > 0"
                            size="small"
                            @click="toggleSessions(row)"
                        >
                            {{ expanded === row.namespace ? $t("dsh.employee.hide_sessions") : $t("dsh.employee.show_sessions") }}
                        </el-button>
                    </template>
                </el-table-column>
            </el-table>

            <div v-if="expanded" class="mt-3">
                <el-table
                    v-if="sessions(expanded).length > 0"
                    :data="sessions(expanded)"
                    size="small"
                    border
                >
                    <el-table-column prop="sessionId" label="sessionId" min-width="280">
                        <template #default="{row}">
                            <code>{{ row.sessionId }}</code>
                        </template>
                    </el-table-column>
                    <el-table-column :label="$t('dsh.employee.reply')" width="80" align="center">
                        <template #default="{row}">
                            <el-tag v-if="row.hasReply" size="small" type="success">✓</el-tag>
                            <el-tag v-else size="small" type="info">—</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column :label="$t('dsh.employee.stderr')" width="80" align="center">
                        <template #default="{row}">
                            <el-tag v-if="row.hasStderr" size="small" type="warning">✓</el-tag>
                            <el-tag v-else size="small" type="info">—</el-tag>
                        </template>
                    </el-table-column>
                    <el-table-column :label="$t('actions')" width="180" align="center">
                        <template #default="{row}">
                            <el-button size="small" @click="viewReply(row.sessionId)">
                                {{ $t("dsh.employee.view_reply") }}
                            </el-button>
                            <el-button size="small" @click="viewStderr(row.sessionId)">
                                {{ $t("dsh.employee.view_stderr") }}
                            </el-button>
                        </template>
                    </el-table-column>
                </el-table>
            </div>
        </KsCol>
    </KsRow>

    <el-drawer v-model="fileDrawer" :title="fileTitle" size="50%">
        <div v-if="fileLoading"><el-skeleton :rows="6" animated /></div>
        <template v-else-if="fileData">
            <el-alert v-if="fileData.truncated" type="info" :closable="false" class="mb-2">
                {{ $t("dsh.employee.truncated", {size: fileData.size}) }}
            </el-alert>
            <pre class="file-content">{{ fileData.content }}</pre>
        </template>
        <span v-else>{{ $t("dsh.employee.no_content") }}</span>
    </el-drawer>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useRoute} from "vue-router"
    import {useI18n} from "vue-i18n"
    import {ElAlert, ElButton, ElCol, ElDrawer, ElEmpty, ElRow, ElSkeleton, ElTable, ElTableColumn, ElTag} from "element-plus"
    import Refresh from "vue-material-design-icons/Refresh.vue"
    import AccountGroupOutline from "vue-material-design-icons/AccountGroupOutline.vue"

    import Navbar from "../../../components/layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {KsRow, KsCol} from "@kestra-io/design-system"

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

    const fileDrawer = ref(false)
    const fileLoading = ref(false)
    const fileTitle = ref("")
    const fileData = ref<{path: string, size: number, truncated: boolean, content: string} | null>(null)

    const api = (path: string) => `/api/v1${route.params.tenant ? "/" + route.params.tenant : ""}${path}`

    const loadData = async () => {
        loading.value = true
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

    const viewReply = (sessionId: string) => viewFile(expanded.value, `/sessions/${sessionId}/reply.txt`)
    const viewStderr = (sessionId: string) => viewFile(expanded.value, `/sessions/${sessionId}/stderr.log`)

    const viewFile = async (namespace: string, path: string) => {
        fileDrawer.value = true
        fileLoading.value = true
        fileTitle.value = path
        fileData.value = null
        try {
            const res = await fetch(api(`/dsh-employee/${namespace}/file?path=${encodeURIComponent(path)}`), {credentials: "include"})
            if (res.ok) {
                fileData.value = await res.json()
            }
        } finally {
            fileLoading.value = false
        }
    }

    onMounted(loadData)
</script>

<style scoped>
    .employee-ns {
        font-size: 0.85em;
        color: var(--ks-content-secondary);
    }
    .file-content {
        white-space: pre-wrap;
        word-break: break-all;
        font-size: 0.85em;
        max-height: 70vh;
        overflow: auto;
    }
    .text-muted {
        color: var(--ks-content-secondary);
    }
</style>
