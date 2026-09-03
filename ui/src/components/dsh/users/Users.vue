<template>
    <TopNavBar :title="routeInfo.title">
        <template #actions>
            <ul>
                <li>
                    <KsButton :icon="Plus" type="primary" data-test="user-add" @click="openCreate">
                        {{ t("dsh.users.add") }}
                    </KsButton>
                </li>
            </ul>
        </template>
    </TopNavBar>

    <section class="full-container user-directory">
        <div class="user-toolbar">
            <el-input
                v-model="search"
                :placeholder="t('dsh.users.searchPlaceholder')"
                clearable
                class="user-search"
                data-test="user-search"
                @keyup.enter="load()"
            />
            <KsButton :icon="Magnify" type="default" @click="load()">{{ t("search") }}</KsButton>
        </div>

        <el-table
            :data="users"
            v-loading="loading"
            class="user-table"
            data-test="user-table"
            @row-click="onRowClick"
        >
            <el-table-column prop="username" :label="t('dsh.users.username')" min-width="180">
                <template #default="{row}">
                    <b>{{ row.username }}</b>
                </template>
            </el-table-column>
            <el-table-column prop="name" :label="t('dsh.users.name')" min-width="140" />
            <el-table-column prop="email" :label="t('dsh.users.email')" min-width="180" />
            <el-table-column :label="t('dsh.users.roles')" min-width="140">
                <template #default="{row}">
                    <el-tag
                        v-for="role in (row.roles || [])"
                        :key="role"
                        :type="role === 'admin' ? 'danger' : 'primary'"
                        size="small"
                        class="user-role-tag"
                    >
                        {{ role }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column :label="t('dsh.users.state')" width="110">
                <template #default="{row}">
                    <el-tag :type="row.userState === 'ACTIVE' ? 'success' : 'info'" size="small">
                        {{ row.userState }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column :label="t('dsh.users.lastLogin')" width="160">
                <template #default="{row}">
                    <KsDateAgo v-if="row.lastLoginAt" :date="row.lastLoginAt" inverted />
                    <span v-else>—</span>
                </template>
            </el-table-column>
            <el-table-column :label="t('actions')" width="220" fixed="right">
                <template #default="{row}">
                    <KsButton size="small" type="default" @click.stop="openEdit(row)">
                        {{ t("edit") }}
                    </KsButton>
                    <KsButton size="small" type="default" @click.stop="openResetPassword(row)">
                        {{ t("dsh.users.resetPassword") }}
                    </KsButton>
                    <el-popconfirm
                        :title="t('dsh.users.confirmDelete')"
                        width="220"
                        @confirm="removeUser(row)"
                    >
                        <template #reference>
                            <KsButton size="small" type="danger" @click.stop>{{ t("delete") }}</KsButton>
                        </template>
                    </el-popconfirm>
                </template>
            </el-table-column>
        </el-table>

        <!-- create / edit dialog -->
        <el-dialog
            v-model="dialogVisible"
            :title="editing ? t('dsh.users.edit') : t('dsh.users.add')"
            width="520"
            data-test="user-dialog"
        >
            <el-form label-position="top" class="user-form">
                <el-form-item v-if="!editing" :label="t('dsh.users.username')" required>
                    <el-input v-model="form.username" data-test="user-form-username" />
                </el-form-item>
                <el-form-item :label="t('dsh.users.name')" required>
                    <el-input v-model="form.name" data-test="user-form-name" />
                </el-form-item>
                <el-form-item :label="t('dsh.users.email')" required>
                    <el-input v-model="form.email" data-test="user-form-email" />
                </el-form-item>
                <el-form-item v-if="!editing" :label="t('dsh.users.password')">
                    <el-input v-model="form.password" type="password" show-password data-test="user-form-password" />
                </el-form-item>
                <el-form-item :label="t('dsh.users.roles')">
                    <el-select v-model="form.roles" multiple allow-create filterable default-first-option class="user-roles-select">
                        <el-option v-for="role in availableRoles" :key="role" :label="role" :value="role" />
                    </el-select>
                </el-form-item>
                <el-form-item :label="t('dsh.users.state')">
                    <el-select v-model="form.userState" class="user-state-select">
                        <el-option label="ACTIVE" value="ACTIVE" />
                        <el-option label="INACTIVE" value="INACTIVE" />
                    </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <KsButton type="default" @click="dialogVisible = false">{{ t("cancel") }}</KsButton>
                <KsButton type="primary" data-test="user-form-submit" @click="submit">
                    {{ t("save") }}
                </KsButton>
            </template>
        </el-dialog>

        <!-- reset password dialog -->
        <el-dialog v-model="passwordDialogVisible" :title="t('dsh.users.resetPassword')" width="440">
            <el-form label-position="top">
                <el-form-item :label="t('dsh.users.newPassword')" required>
                    <el-input v-model="newPassword" type="password" show-password data-test="password-input" />
                </el-form-item>
            </el-form>
            <template #footer>
                <KsButton type="default" @click="passwordDialogVisible = false">{{ t("cancel") }}</KsButton>
                <KsButton type="primary" data-test="password-submit" @click="submitPassword">
                    {{ t("save") }}
                </KsButton>
            </template>
        </el-dialog>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, reactive, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {useRouter} from "vue-router"
    import {ElMessage} from "element-plus"
    import Plus from "vue-material-design-icons/Plus.vue"
    import Magnify from "vue-material-design-icons/Magnify.vue"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {getCsrfToken} from "../../../utils/csrf"

    const router = useRouter()
    const {t} = useI18n({useScope: "global"})
    const routeInfo = computed(() => ({title: t("dsh.users.title")}))
    useRouteContext(routeInfo)

    interface UserRow {
        username: string;
        name: string;
        email: string;
        userState: string;
        roles: string[];
        createdAt: string;
        lastLoginAt: string;
    }

    const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin

    const users = ref<UserRow[]>([])
    const loading = ref(false)
    const search = ref("")
    const availableRoles = ["admin", "user"]

    const dialogVisible = ref(false)
    const editing = ref<UserRow | null>(null)
    const form = reactive({
        username: "",
        name: "",
        email: "",
        password: "",
        roles: [] as string[],
        userState: "ACTIVE",
    })

    const passwordDialogVisible = ref(false)
    const newPassword = ref("")
    const passwordTarget = ref<UserRow | null>(null)

    function api(url: string, options: RequestInit = {}) {
        const headers = new Headers(options.headers)
        headers.set("Content-Type", "application/json")
        // Same-origin cookie auth triggers Kestra's CsrfTokenFilter on non-GET: forward the
        // token the server injected into the /ui/ meta tag (browser auto-attaches the
        // HTTPOnly csrfToken cookie from the same response).
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
            const params = new URLSearchParams()
            if (search.value.trim()) {
                params.set("search", search.value.trim())
            }
            params.set("size", "500")
            const res = await api(`/api/v1/oidc/users?${params.toString()}`)
            if (!res.ok) {
                if (res.status === 401 || res.status === 403) {
                    ElMessage.error(t("dsh.users.notAdmin"))
                    return
                }
                throw new Error(await res.text())
            }
            users.value = await res.json()
        } catch (e) {
            ElMessage.error(t("dsh.users.loadFailed", {message: String(e)}))
        } finally {
            loading.value = false
        }
    }

    function resetForm() {
        form.username = ""
        form.name = ""
        form.email = ""
        form.password = ""
        form.roles = []
        form.userState = "ACTIVE"
    }

    function openCreate() {
        editing.value = null
        resetForm()
        dialogVisible.value = true
    }

    function openEdit(row: UserRow) {
        editing.value = row
        form.username = row.username
        form.name = row.name
        form.email = row.email
        form.password = ""
        form.roles = [...(row.roles || [])]
        form.userState = row.userState
        dialogVisible.value = true
    }

    async function submit() {
        if (editing.value) {
            const res = await api(`/api/v1/oidc/users/${encodeURIComponent(editing.value.username)}`, {
                method: "PUT",
                body: JSON.stringify({
                    name: form.name,
                    email: form.email,
                    userState: form.userState,
                }),
            })
            if (!res.ok) {
                ElMessage.error(await res.text())
                return
            }
            // persist roles too (authorisation)
            await api(`/api/v1/oidc/users/${encodeURIComponent(editing.value.username)}/roles`, {
                method: "PUT",
                body: JSON.stringify({roles: form.roles}),
            })
            ElMessage.success(t("dsh.users.saved"))
        } else {
            const res = await api("/api/v1/oidc/users", {
                method: "POST",
                body: JSON.stringify({
                    username: form.username,
                    name: form.name,
                    email: form.email,
                    password: form.password || undefined,
                    roles: form.roles,
                    userState: form.userState,
                }),
            })
            if (!res.ok) {
                ElMessage.error(await res.text())
                return
            }
            ElMessage.success(t("dsh.users.created"))
        }
        dialogVisible.value = false
        load()
    }

    function openResetPassword(row: UserRow) {
        passwordTarget.value = row
        newPassword.value = ""
        passwordDialogVisible.value = true
    }

    async function submitPassword() {
        if (!passwordTarget.value || !newPassword.value) {
            ElMessage.error(t("dsh.users.passwordRequired"))
            return
        }
        const res = await api(`/api/v1/oidc/users/${encodeURIComponent(passwordTarget.value.username)}/password`, {
            method: "POST",
            body: JSON.stringify({password: newPassword.value}),
        })
        if (!res.ok) {
            ElMessage.error(await res.text())
            return
        }
        ElMessage.success(t("dsh.users.passwordUpdated"))
        passwordDialogVisible.value = false
    }

    async function removeUser(row: UserRow) {
        const res = await api(`/api/v1/oidc/users/${encodeURIComponent(row.username)}`, {method: "DELETE"})
        if (!res.ok && res.status !== 204) {
            ElMessage.error(await res.text())
            return
        }
        ElMessage.success(t("dsh.users.deleted"))
        load()
    }

    function onRowClick(row: UserRow) {
        router.push({name: "admin/users/update", params: {id: row.username}})
    }

    onMounted(load)
</script>

<style scoped lang="scss">
    .user-directory {
        display: flex;
        flex-direction: column;
        gap: var(--ks-spacing-4);
        padding: var(--ks-spacing-4);
    }

    .user-toolbar {
        display: flex;
        gap: var(--ks-spacing-2);
        align-items: center;
    }

    .user-search {
        max-width: 320px;
    }

    .user-table {
        width: 100%;
        cursor: pointer;
    }

    .user-role-tag {
        margin-right: 4px;
    }

    .user-form, .user-roles-select, .user-state-select {
        width: 100%;
    }
</style>
