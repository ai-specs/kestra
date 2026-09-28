<template>
    <TopNavBar :title="employee?.sub ?? namespace" :breadcrumb="breadcrumb">
        <template #actions>
            <code v-if="employee" class="ns-code me-2 align-self-center">{{ employee.namespace }}</code>
            <KsButton :icon="RefreshIcon" @click="loadDetail">{{ $t("refresh") }}</KsButton>
        </template>
    </TopNavBar>

    <section v-if="error" class="full-container p-4">
        <KsAlert :title="$t(error)" type="error" />
        <KsButton class="mt-3" :icon="ArrowLeftIcon" @click="router.push({name: 'employees/list'})">
            {{ $t("dsh.employee.back_to_list") }}
        </KsButton>
    </section>

    <Tabs v-else :key="namespace" :tabs="tabs" routeName="employees/update" vertical />
</template>

<script setup lang="ts">
    import {computed, onMounted, ref, watch} from "vue"
    import {useRoute, useRouter} from "vue-router"
    import {useI18n} from "vue-i18n"
    import RefreshIcon from "vue-material-design-icons/Refresh.vue"
    import ArrowLeftIcon from "vue-material-design-icons/ArrowLeft.vue"
    import {KsAlert, KsButton} from "@kestra-io/design-system"

    import TopNavBar from "../../layout/TopNavBar.vue"
    import Tabs from "../../Tabs.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import EmployeeSessions from "./EmployeeSessions.vue"
    import EmployeeFiles from "./EmployeeFiles.vue"

    const route = useRoute()
    const router = useRouter()
    const {t} = useI18n({useScope: "global"})

    const namespace = computed(() => String(route.params.namespace ?? ""))
    const employee = ref<{namespace: string, sub: string, sessionCount: number} | null>(null)
    // i18n key shown in the alert when the detail endpoint rejects (403/404)
    const error = ref("")

    const context = computed(() => ({title: employee.value?.sub ?? namespace.value}))
    useRouteContext(context)

    const breadcrumb = computed(() => [
        {label: t("dsh.employee.label"), link: {name: "employees/list"}},
    ])

    const tabs = computed(() => [
        {
            name: "sessions",
            title: t("dsh.employee.tab_sessions"),
            component: EmployeeSessions,
            props: {namespace: namespace.value},
        },
        {
            name: "files",
            title: t("dsh.employee.tab_files"),
            component: EmployeeFiles,
            props: {namespace: namespace.value},
            maximized: true,
        },
    ])

    const api = (path: string) => `/api/v1${route.params.tenant ? "/" + route.params.tenant : ""}${path}`

    const loadDetail = async () => {
        error.value = ""
        employee.value = null
        const res = await fetch(api(`/dsh-employee/${encodeURIComponent(namespace.value)}`), {credentials: "include"})
        if (res.status === 403) {
            error.value = "dsh.employee.forbidden"
            return
        }
        if (res.status === 404) {
            error.value = "dsh.employee.not_found"
            return
        }
        if (res.ok) {
            employee.value = await res.json()
        }
    }

    watch(namespace, loadDetail)
    onMounted(loadDetail)
</script>

<style scoped>
    .ns-code {
        font-size: 0.85em;
        color: var(--ks-text-secondary);
    }
</style>
