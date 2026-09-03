<template>
    <TopNavBar :title="routeInfo.title" />

    <section class="full-container role-directory">
        <el-row :gutter="16" class="role-layout">
            <el-col :span="6">
                <div class="role-list-panel">
                    <div class="panel-title">{{ t("dsh.roles.title") }}</div>
                    <el-menu
                        :default-active="selectedRole"
                        class="role-menu"
                        data-test="role-menu"
                        @select="(key: string) => selectedRole = key"
                    >
                        <el-menu-item v-for="role in roleNames" :key="role" :index="role">
                            <el-tag :type="role === 'admin' ? 'danger' : 'primary'" size="small">
                                {{ role }}
                            </el-tag>
                            <span class="role-count">{{ countByRole(role) }}</span>
                        </el-menu-item>
                    </el-menu>
                </div>
            </el-col>

            <el-col :span="18">
                <div class="role-members-panel">
                    <div class="panel-title">
                        {{ t("dsh.roles.members", {role: selectedRole}) }}
                        <el-select
                            v-model="memberToAdd"
                            filterable
                            :placeholder="t('dsh.roles.addMemberPlaceholder')"
                            class="member-add-select"
                            data-test="member-add"
                            @change="addMember"
                        >
                            <el-option
                                v-for="u in nonMembers"
                                :key="u.username"
                                :label="`${u.username} (${u.name})`"
                                :value="u.username"
                            />
                        </el-select>
                    </div>

                    <el-table
                        :data="members"
                        v-loading="loading"
                        class="member-table"
                        data-test="member-table"
                    >
                        <el-table-column prop="username" :label="t('dsh.users.username')" min-width="180">
                            <template #default="{row}">
                                <b>{{ row.username }}</b>
                            </template>
                        </el-table-column>
                        <el-table-column prop="name" :label="t('dsh.users.name')" min-width="140" />
                        <el-table-column prop="email" :label="t('dsh.users.email')" min-width="180" />
                        <el-table-column :label="t('actions')" width="120" fixed="right">
                            <template #default="{row}">
                                <el-popconfirm
                                    :title="t('dsh.roles.confirmRemove', {user: row.username, role: selectedRole})"
                                    width="240"
                                    @confirm="removeMember(row)"
                                >
                                    <template #reference>
                                        <KsButton size="small" type="danger">{{ t("dsh.roles.remove") }}</KsButton>
                                    </template>
                                </el-popconfirm>
                            </template>
                        </el-table-column>
                    </el-table>
                </div>
            </el-col>
        </el-row>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {ElMessage} from "element-plus"
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
        height: 100%;
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
