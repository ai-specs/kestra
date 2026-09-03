<template>
    <TopNavBar :title="routeInfo.title" />

    <section class="full-container role-directory">
        <div class="role-layout">
            <aside class="role-list-panel">
                <div class="panel-title">{{ t("dsh.roles.title") }}</div>
                <KsMenu :default-active="selectedRole" class="role-menu" data-test="role-menu" @select="onRoleSelect">
                    <KsMenuItem v-for="role in roleNames" :key="role" :index="role">
                        <KsTag :type="role === 'admin' ? 'danger' : 'primary'" size="small" effect="light">
                            {{ role }}
                        </KsTag>
                        <span class="role-count">{{ countByRole(role) }}</span>
                    </KsMenuItem>
                </KsMenu>
            </aside>

            <div class="role-members-panel">
                <div class="panel-title">
                    <span>{{ t("dsh.roles.members", {role: selectedRole}) }}</span>
                    <KsSelect
                        v-model="memberToAdd"
                        filterable
                        :placeholder="t('dsh.roles.addMemberPlaceholder')"
                        class="member-add-select"
                        data-test="member-add"
                        @change="addMember"
                    >
                        <KsOption
                            v-for="u in nonMembers"
                            :key="u.username"
                            :label="`${u.username} (${u.name})`"
                            :value="u.username"
                        />
                    </KsSelect>
                </div>

                <KsTable
                    :data="members"
                    v-loading="loading"
                    class="member-table"
                    data-test="member-table"
                >
                    <KsTableColumn prop="username" :label="t('dsh.users.username')" min-width="180">
                        <template #default="{row}">
                            <b>{{ row.username }}</b>
                        </template>
                    </KsTableColumn>
                    <KsTableColumn prop="name" :label="t('dsh.users.name')" min-width="140" />
                    <KsTableColumn prop="email" :label="t('dsh.users.email')" min-width="180" />
                    <KsTableColumn :label="t('actions')" width="120" fixed="right">
                        <template #default="{row}">
                            <KsButton size="small" type="danger" data-test="member-remove" @click="confirmRemove(row)">
                                {{ t("dsh.roles.remove") }}
                            </KsButton>
                        </template>
                    </KsTableColumn>
                </KsTable>
            </div>
        </div>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {ElMessage, ElMessageBox} from "element-plus"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {getCsrfToken} from "../../../utils/csrf"

    interface UserRow {
        username: string;
        name: string;
        email: string;
        userState: string;
        roles: string[];
        createdAt: string;
        lastLoginAt: string;
    }

    const {t} = useI18n({useScope: "global"})
    const routeInfo = computed(() => ({title: t("dsh.roles.title")}))
    useRouteContext(routeInfo)

    const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin

    const users = ref<UserRow[]>([])
    const loading = ref(false)
    const selectedRole = ref("user")
    const memberToAdd = ref("")

    const roleNames = computed(() => {
        const set = new Set<string>()
        users.value.forEach((u) => (u.roles || []).forEach((r) => set.add(r)))
        return Array.from(set).sort()
    })

    const members = computed(() =>
        users.value.filter((u) => (u.roles || []).includes(selectedRole.value)),
    )

    const nonMembers = computed(() =>
        users.value.filter((u) => !(u.roles || []).includes(selectedRole.value) && u.userState === "ACTIVE"),
    )

    function countByRole(role: string) {
        return users.value.filter((u) => (u.roles || []).includes(role)).length
    }

    function onRoleSelect(index: string) {
        selectedRole.value = index
    }

    function api(url: string, options: RequestInit = {}) {
        const headers = new Headers(options.headers)
        headers.set("Content-Type", "application/json")
        const csrf = getCsrfToken()
        if (csrf) headers.set("X-CSRF-TOKEN", csrf)
        return fetch(`${API_BASE}${url}`, {
            credentials: "include",
            headers,
            ...options,
        })
    }

    async function load() {
        loading.value = true
        try {
            const res = await api("/api/v1/oidc/users?size=500")
            if (!res.ok) {
                if (res.status === 401 || res.status === 403) {
                    ElMessage.error(t("dsh.users.notAdmin"))
                    return
                }
                throw new Error(await res.text())
            }
            users.value = await res.json()
            if (!roleNames.value.includes(selectedRole.value) && roleNames.value.length > 0) {
                selectedRole.value = roleNames.value[0]
            }
        } catch (e) {
            ElMessage.error(t("dsh.users.loadFailed", {message: String(e)}))
        } finally {
            loading.value = false
        }
    }

    async function addMember(username: string) {
        if (!username) return
        const target = users.value.find((u) => u.username === username)
        if (!target) return
        const next = [...new Set([...(target.roles || []), selectedRole.value])]
        const res = await api(`/api/v1/oidc/users/${encodeURIComponent(username)}/roles`, {
            method: "PUT",
            body: JSON.stringify({roles: next}),
        })
        memberToAdd.value = ""
        if (!res.ok) {
            ElMessage.error(await res.text())
            return
        }
        ElMessage.success(t("dsh.roles.memberAdded", {user: username, role: selectedRole.value}))
        load()
    }

    async function confirmRemove(row: UserRow) {
        try {
            await ElMessageBox.confirm(
                t("dsh.roles.confirmRemove", {user: row.username, role: selectedRole.value}),
                t("dsh.roles.remove"),
                {type: "warning", confirmButtonText: t("dsh.roles.remove"), cancelButtonText: t("cancel")},
            )
        } catch {
            return
        }
        await removeMember(row)
    }

    async function removeMember(row: UserRow) {
        const next = (row.roles || []).filter((r) => r !== selectedRole.value)
        const res = await api(`/api/v1/oidc/users/${encodeURIComponent(row.username)}/roles`, {
            method: "PUT",
            body: JSON.stringify({roles: next}),
        })
        if (!res.ok) {
            ElMessage.error(await res.text())
            return
        }
        ElMessage.success(t("dsh.roles.memberRemoved", {user: row.username, role: selectedRole.value}))
        load()
    }

    onMounted(load)
</script>

<style scoped lang="scss">
    .role-directory {
        padding: var(--ks-spacing-4);
    }

    .role-layout {
        display: flex;
        gap: var(--ks-spacing-4);
        height: 100%;
    }

    .role-list-panel {
        width: 240px;
        flex-shrink: 0;
    }

    .role-members-panel {
        flex: 1;
        min-width: 0;
    }

    .role-list-panel, .role-members-panel {
        border: 1px solid var(--ks-border-color);
        border-radius: var(--ks-radius-sm);
        padding: var(--ks-spacing-3);
        background: var(--ks-background-primary);
    }

    .panel-title {
        font-weight: 600;
        display: flex;
        align-items: center;
        gap: var(--ks-spacing-3);
        margin-bottom: var(--ks-spacing-3);
    }

    .role-menu {
        border-right: none;
    }

    .role-count {
        margin-left: auto;
        color: var(--ks-text-muted);
    }

    .member-add-select {
        margin-left: auto;
        width: 280px;
    }
</style>
