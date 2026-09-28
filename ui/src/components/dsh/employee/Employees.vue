<template>
    <TopNavBar :title="routeInfo.title">
        <template #actions>
            <KsButton :icon="RefreshIcon" @click="loadData">{{ t("refresh") }}</KsButton>
        </template>
    </TopNavBar>

    <section v-if="loading" class="full-container p-4">
        <KsSkeleton :rows="4" animated />
    </section>

    <section v-else-if="employees.length === 0" class="full-container p-4">
        <span class="text-muted">{{ t("dsh.employee.empty") }}</span>
    </section>

    <section v-else class="full-container p-4">
        <KsTable
            :data="employees"
            :fit="true"
            class="employee-table"
            @row-click="openEmployee"
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
                <template #default>
                    <ChevronRightIcon class="row-hint" />
                </template>
            </KsTableColumn>
        </KsTable>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useRoute, useRouter} from "vue-router"
    import {useI18n} from "vue-i18n"
    import RefreshIcon from "vue-material-design-icons/Refresh.vue"
    import ChevronRightIcon from "vue-material-design-icons/ChevronRight.vue"
    import AccountGroupOutline from "vue-material-design-icons/AccountGroupOutline.vue"
    import {KsButton, KsSkeleton} from "@kestra-io/design-system"

    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"

    const route = useRoute()
    const router = useRouter()
    const {t} = useI18n({useScope: "global"})

    const routeInfo = computed(() => ({title: t("dsh.employee.label")}))
    useRouteContext(routeInfo)

    interface EmployeeRow {
        namespace: string
        sub: string
        sessionCount: number
    }

    const loading = ref(false)
    const employees = ref<EmployeeRow[]>([])

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

    // 与 namespaces 列表同款交互：整行进入详情页（路由化子页，不再页内展开）
    const openEmployee = (emp: EmployeeRow) => {
        router.push({name: "employees/update/sessions", params: {namespace: emp.namespace}})
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
    .row-hint {
        color: var(--ks-content-secondary, #999);
        font-size: 0.9em;
    }
    .employee-table :deep(tbody tr) {
        cursor: pointer;
    }
</style>
