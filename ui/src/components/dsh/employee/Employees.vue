<template>
    <Navbar :title="routeInfo.title">
        <template #actions>
            <el-button @click="loadData" :icon="Refresh">{{ $t("refresh") }}</el-button>
        </template>
    </Navbar>

    <KsRow class="row-padding">
        <KsCol v-if="loading" class="p-3"><el-skeleton :rows="3" animated /></KsCol>
        <KsCol v-else-if="employees.length === 0" class="p-3">
            <span>{{ $t("dsh.employee.empty") }}</span>
        </KsCol>

        <KsCol v-for="emp in employees" :key="emp.namespace" class="p-2">
            <el-card shadow="never" class="employee-card">
                <div class="d-flex align-items-center justify-content-between">
                    <div class="d-flex align-items-center">
                        <AccountGroupOutline class="me-2 icon" />
                        <div>
                            <div class="employee-sub">{{ emp.sub }}</div>
                            <code class="employee-ns">{{ emp.namespace }}</code>
                        </div>
                    </div>
                    <div class="d-flex align-items-center gap-2">
                        <el-tag size="small">{{ $t("dsh.employee.sessions", {count: emp.sessionCount}) }}</el-tag>
                        <el-button size="small" @click="toggleSessions(emp)">
                            {{ expanded === emp.namespace ? $t("dsh.employee.hide_sessions") : $t("dsh.employee.show_sessions") }}
                        </el-button>
                    </div>
                </div>

                <div v-if="expanded === emp.namespace" class="mt-3">
                    <el-table
                        v-if="sessions(emp.namespace).length > 0"
                        :data="sessions(emp.namespace)"
                        size="small"
                        border
                    >
                        <el-table-column prop="sessionId" label="sessionId" min-width="280">
                            <template #default="{row}">
                                <code>{{ row.sessionId }}</code>
                            </template>
                        </el-table-column>
                        <el-table-column :label="$t('dsh.employee.reply')" width="90">
                            <template #default="{row}">
                                <el-tag v-if="row.hasReply" size="small" type="success">✓</el-tag>
                                <el-tag v-else size="small" type="info">—</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column :label="$t('dsh.employee.stderr')" width="90">
                            <template #default="{row}">
                                <el-tag v-if="row.hasStderr" size="small" type="warning">✓</el-tag>
                                <el-tag v-else size="small" type="info">—</el-tag>
                            </template>
                        </el-table-column>
                        <el-table-column :label="$t('actions')" width="200">
                            <template #default="{row}">
                                <el-button size="small" @click="viewFile(emp.namespace, `/sessions/${row.sessionId}/reply.txt`)">
                                    {{ $t("dsh.employee.view_reply") }}
                                </el-button>
                                <el-button size="small" @click="viewFile(emp.namespace, `/sessions/${row.sessionId}/stderr.log`)">
                                    {{ $t("dsh.employee.view_stderr") }}
                                </el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                    <span v-else class="text-muted">{{ $t("dsh.employee.no_sessions") }}</span>
                </div>
            </el-card>
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
    import {ElButton, ElCard, ElCol, ElDrawer, ElRow, ElSkeleton, ElTable, ElTableColumn, ElTag, ElAlert} from "element-plus"
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
    .employee-card {width: 100%;}
    .employee-sub {font-weight: 600;}
    .employee-ns {font-size: 0.85em; color: var(--ks-content-secondary);}
    .file-content {
        white-space: pre-wrap;
        word-break: break-all;
        font-size: 0.85em;
        max-height: 70vh;
        overflow: auto;
    }
    .gap-2 {gap: 8px;}
</style>
