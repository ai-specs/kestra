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
            <KsInput
                v-model="search"
                :placeholder="t('dsh.users.searchPlaceholder')"
                clearable
                class="user-search"
                data-test="user-search"
                @keyup.enter="load()"
            />
            <KsSelect v-model="typeFilter" class="user-type-filter" data-test="user-type-filter" @change="load()">
                <KsOption :label="t('dsh.users.allTypes')" value="" />
                <KsOption :label="t('dsh.users.human')" value="human" />
                <KsOption :label="t('dsh.users.machine')" value="machine" />
            </KsSelect>
            <KsButton :icon="Magnify" type="default" @click="load()">{{ t("search") }}</KsButton>
        </div>

        <KsTable
            :data="users"
            v-loading="loading"
            :fit="true"
            class="user-table"
            data-test="user-table"
            @row-click="onRowClick"
        >
            <KsTableColumn prop="username" :label="t('dsh.users.username')" min-width="150">
                <template #default="{row}">
                    <b>{{ row.username }}</b>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.users.type')" width="90">
                <template #default="{row}">
                    <KsTag :type="row.type === 'machine' ? 'warning' : 'success'" size="small" effect="light">
                        {{ row.type === 'machine' ? t('dsh.users.machine') : t('dsh.users.human') }}
                    </KsTag>
                </template>
            </KsTableColumn>
            <KsTableColumn prop="name" :label="t('dsh.users.name')" min-width="120" />
            <KsTableColumn prop="email" :label="t('dsh.users.email')" min-width="150" />
            <KsTableColumn :label="t('dsh.users.roles')" min-width="120">
                <template #default="{row}">
                    <KsTag
                        v-for="role in (row.roles || [])"
                        :key="role"
                        :type="role === 'admin' ? 'danger' : 'primary'"
                        size="small"
                        effect="light"
                        class="user-role-tag"
                    >
                        {{ role }}
                    </KsTag>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.users.state')" width="90">
                <template #default="{row}">
                    <KsTag :type="row.userState === 'ACTIVE' ? 'success' : 'info'" size="small" effect="light">
                        {{ row.userState }}
                    </KsTag>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.users.lastLogin')" width="130">
                <template #default="{row}">
                    <KsDateAgo v-if="row.lastLoginAt" :date="row.lastLoginAt" inverted />
                    <span v-else>—</span>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('actions')" width="180">
                <template #default="{row}">
                    <KsButton size="small" type="default" @click.stop="openEdit(row)">
                        {{ t("edit") }}
                    </KsButton>
                    <KsButton
                        v-if="row.type !== 'machine'"
                        size="small"
                        type="default"
                        @click.stop="openResetPassword(row)"
                    >
                        {{ t("dsh.users.resetPassword") }}
                    </KsButton>
                    <KsButton v-else size="small" type="default" @click.stop="openResetPassword(row)">
                        {{ t("dsh.users.rotateSecret") }}
                    </KsButton>
                    <KsButton size="small" type="danger" data-test="user-delete" @click.stop="confirmRemove(row)">
                        {{ t("delete") }}
                    </KsButton>
                </template>
            </KsTableColumn>
        </KsTable>

        <!-- create / edit dialog -->
        <KsDialog
            v-model="dialogVisible"
            :title="editing ? t('dsh.users.edit') : t('dsh.users.add')"
            width="520"
            data-test="user-dialog"
        >
            <KsForm label-position="top" class="user-form">
                <KsFormItem v-if="!editing" :label="t('dsh.users.type')" required>
                    <KsSelect v-model="form.type" class="user-type-select" data-test="user-form-type">
                        <KsOption :label="t('dsh.users.human')" value="human" />
                        <KsOption :label="t('dsh.users.machine')" value="machine" />
                    </KsSelect>
                </KsFormItem>
                <KsFormItem v-if="!editing" :label="t('dsh.users.username')" required>
                    <KsInput v-model="form.username" data-test="user-form-username" />
                </KsFormItem>
                <KsFormItem :label="t('dsh.users.name')" required>
                    <KsInput v-model="form.name" data-test="user-form-name" />
                </KsFormItem>
                <KsFormItem v-if="form.type === 'human'" :label="t('dsh.users.email')" required>
                    <KsInput v-model="form.email" data-test="user-form-email" />
                </KsFormItem>
                <KsFormItem v-if="!editing && form.type === 'machine'" :label="t('dsh.users.description')">
                    <KsInput v-model="form.description" data-test="user-form-description" />
                </KsFormItem>
                <KsFormItem v-if="!editing && form.type === 'human'" :label="t('dsh.users.password')">
                    <KsInput v-model="form.password" type="password" show-password data-test="user-form-password" />
                </KsFormItem>
                <KsFormItem v-if="!editing && form.type === 'machine'" :label="t('dsh.users.secret')">
                    <KsInput v-model="form.secret" show-password data-test="user-form-secret"
                        :placeholder="t('dsh.users.secretPlaceholder')" />
                </KsFormItem>
                <KsFormItem :label="t('dsh.users.roles')">
                    <KsSelect v-model="form.roles" multiple allow-create filterable class="user-roles-select">
                        <KsOption v-for="role in availableRoles" :key="role" :label="role" :value="role" />
                    </KsSelect>
                </KsFormItem>
                <KsFormItem :label="t('dsh.users.state')">
                    <KsSelect v-model="form.userState" class="user-state-select">
                        <KsOption label="ACTIVE" value="ACTIVE" />
                        <KsOption label="INACTIVE" value="INACTIVE" />
                    </KsSelect>
                </KsFormItem>
            </KsForm>
            <template #footer>
                <KsButton type="default" @click="dialogVisible = false">{{ t("cancel") }}</KsButton>
                <KsButton type="primary" data-test="user-form-submit" @click="submit">
                    {{ t("save") }}
                </KsButton>
            </template>
        </KsDialog>

        <!-- reset password / rotate secret dialog -->
        <KsDialog
            v-model="passwordDialogVisible"
            :title="passwordTarget && passwordTarget.type === 'machine' ? t('dsh.users.rotateSecret') : t('dsh.users.resetPassword')"
            width="440"
        >
            <KsForm label-position="top">
                <KsFormItem
                    :label="passwordTarget && passwordTarget.type === 'machine' ? t('dsh.users.newSecret') : t('dsh.users.newPassword')"
                    required
                >
                    <KsInput v-model="newPassword" type="password" show-password data-test="password-input" />
                </KsFormItem>
            </KsForm>
            <template #footer>
                <KsButton type="default" @click="passwordDialogVisible = false">{{ t("cancel") }}</KsButton>
                <KsButton type="primary" data-test="password-submit" @click="submitPassword">
                    {{ t("save") }}
                </KsButton>
            </template>
        </KsDialog>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, reactive, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {useRouter} from "vue-router"
    import {ElMessage, ElMessageBox} from "element-plus"
    import Plus from "vue-material-design-icons/Plus.vue"
    import Magnify from "vue-material-design-icons/Magnify.vue"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {getCsrfToken} from "../../../utils/csrf"
    import {SessionExpiredError, sessionExpired} from "../../../utils/dshSession"

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
        type: string;
        createdAt: string;
        lastLoginAt: string;
    }

    const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin

    const users = ref<UserRow[]>([])
    const loading = ref(false)
    const search = ref("")
    const typeFilter = ref("")
    const availableRoles = ["admin", "user"]

    const dialogVisible = ref(false)
    const editing = ref<UserRow | null>(null)
    // Default to least-privilege: an admin creating a user must explicitly grant admin.
    const form = reactive({
        username: "",
        name: "",
        email: "",
        password: "",
        description: "",
        secret: "",
        type: "human",
        roles: ["user"] as string[],
        userState: "ACTIVE",
    })

    const passwordDialogVisible = ref(false)
    const newPassword = ref("")
    const passwordTarget = ref<UserRow | null>(null)

    async function api(url: string, options: RequestInit = {}) {
        const headers = new Headers(options.headers)
        headers.set("Content-Type", "application/json")
        // Same-origin cookie auth triggers Kestra's CsrfTokenFilter on non-GET: forward the
        // token the server injected into the /ui/ meta tag (browser auto-attaches the
        // HTTPOnly csrfToken cookie from the same response).
        const csrf = getCsrfToken()
        if (csrf) headers.set("X-CSRF-TOKEN", csrf)
        const res = await fetch(`${API_BASE}${url}`, {
            credentials: "include",
            headers,
            ...options,
        })
        if (res.status === 401) {
            // Session expired (JWT / oidc_session) while kestraBasicAuthenticated still
            // says we are logged in — never report this as a permission problem. Redirect
            // through the OIDC logout endpoint (clears cookies, lands on the IdP login).
            sessionExpired()
        }
        return res
    }

    async function load() {
        loading.value = true
        try {
            const params = new URLSearchParams()
            if (search.value.trim()) {
                params.set("search", search.value.trim())
            }
            if (typeFilter.value) {
                params.set("type", typeFilter.value)
            }
            params.set("size", "500")
            const res = await api(`/api/v1/oidc/users?${params.toString()}`)
            if (res.status === 403) {
                ElMessage.error(t("dsh.users.notAdmin"))
                return
            }
            if (!res.ok) {
                throw new Error(await res.text())
            }
            users.value = await res.json()
        } catch (e) {
            if (e instanceof SessionExpiredError) return
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
        form.description = ""
        form.secret = ""
        form.type = "human"
        form.roles = ["user"]
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
        form.description = ""
        form.secret = ""
        form.type = row.type || "human"
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
                    email: form.type === "human" ? form.email : undefined,
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
        } else if (form.type === "machine") {
            const res = await api("/api/v1/oidc/users", {
                method: "POST",
                body: JSON.stringify({
                    username: form.username,
                    name: form.name,
                    description: form.description,
                    secret: form.secret || undefined,
                    type: "machine",
                    roles: form.roles,
                    userState: form.userState,
                }),
            })
            if (!res.ok) {
                ElMessage.error(await res.text())
                return
            }
            ElMessage.success(t("dsh.users.created"))
        } else {
            const res = await api("/api/v1/oidc/users", {
                method: "POST",
                body: JSON.stringify({
                    username: form.username,
                    name: form.name,
                    email: form.email,
                    password: form.password || undefined,
                    type: "human",
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
            ElMessage.error(
                passwordTarget.value?.type === "machine"
                    ? t("dsh.users.secretRequired")
                    : t("dsh.users.passwordRequired"),
            )
            return
        }
        const target = passwordTarget.value
        const endpoint = target.type === "machine"
            ? `/api/v1/oidc/users/${encodeURIComponent(target.username)}/secret`
            : `/api/v1/oidc/users/${encodeURIComponent(target.username)}/password`
        const bodyKey = target.type === "machine" ? "secret" : "password"
        const res = await api(endpoint, {
            method: "POST",
            body: JSON.stringify({[bodyKey]: newPassword.value}),
        })
        if (!res.ok) {
            ElMessage.error(await res.text())
            return
        }
        ElMessage.success(
            target.type === "machine" ? t("dsh.users.secretRotated") : t("dsh.users.passwordUpdated"),
        )
        passwordDialogVisible.value = false
    }

    async function confirmRemove(row: UserRow) {
        try {
            await ElMessageBox.confirm(
                t("dsh.users.confirmDelete"),
                t("delete"),
                {type: "warning", confirmButtonText: t("delete"), cancelButtonText: t("cancel")},
            )
        } catch {
            return
        }
        await removeUser(row)
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

    .user-type-filter {
        width: 150px;
    }

    .user-table {
        width: 100%;
        cursor: pointer;
        /* KsTable root defaults to overflow-x:hidden; in fit mode the scrollable-x
           class is absent, so narrow viewports would clip the right columns. Make the
           root a real horizontal scroll container. */
        :deep(.kel-table) {
            overflow-x: auto !important;
        }
    }

    .user-role-tag {
        margin-right: 4px;
    }

    .user-form, .user-roles-select, .user-state-select {
        width: 100%;
    }
</style>
