<template>
    <TopNavBar :title="routeInfo.title" />

    <section class="full-container project-detail">
        <!-- 面包屑导航 -->
        <nav class="project-breadcrumb">
            <router-link :to="{name: 'admin/project'}" class="breadcrumb-link">
                {{ t("dsh.project.title") }}
            </router-link>
            <span class="breadcrumb-separator">/</span>
            <span class="breadcrumb-current">{{ projectName }}</span>
        </nav>

        <!-- 项目头部 -->
        <div class="project-header">
            <div class="project-title-row">
                <h1 class="project-name">{{ projectName }}</h1>
                <KsTag type="success" size="small" effect="light">{{ t("dsh.project.defaultProject") }}</KsTag>
            </div>
            <p class="project-desc">{{ projectDesc }}</p>
        </div>

        <!-- Tab 导航 -->
        <KsTabs v-model="activeTab" class="project-tabs" type="box">
            <!-- 概览 Tab -->
            <KsTabPane :label="t('dsh.project.overview')" name="summary">
                <div class="tab-content">
                    <!-- 项目统计（顶部 header 已展示项目名与描述，这里只展示补充信息） -->
                    <div class="info-section">
                        <div class="info-grid">
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.createdAt") }}</span>
                                <span class="info-value">{{ projectCreatedAt }}</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.applications") }}</span>
                                <span class="info-value">{{ clients.length }}</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.roles") }}</span>
                                <span class="info-value">{{ roles.length }}</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.members") }}</span>
                                <span class="info-value">{{ users.length }}</span>
                            </div>
                        </div>
                    </div>

                    <!-- Applications 卡片网格 -->
                    <div class="apps-section">
                        <h3 class="section-title">{{ t("dsh.project.applications") }}</h3>
                        <div class="apps-grid">
                            <div v-for="app in clients" :key="app.clientId" class="app-card" :class="{inactive: app.active === false}" @click="openAppDetail(app)">
                                <div class="app-icon" :class="isPublic(app) ? 'public' : 'confidential'">
                                    {{ (app.clientId || '?').charAt(0).toUpperCase() }}
                                </div>
                                <div class="app-info">
                                    <div class="app-name">{{ app.clientId }}</div>
                                    <div class="app-type">
                                        <KsTag :type="isPublic(app) ? 'info' : 'warning'" size="small" effect="light">
                                            {{ isPublic(app) ? t("dsh.project.public") : t("dsh.project.confidential") }}
                                        </KsTag>
                                    </div>
                                    <div class="app-grants">
                                        <span v-for="g in (app.grantTypes || [])" :key="g" class="grant-tag">{{ g }}</span>
                                    </div>
                                    <div v-if="app.active === false" class="app-inactive-tag">
                                        <KsTag type="danger" size="small" effect="light">{{ t("dsh.project.inactive") }}</KsTag>
                                    </div>
                                </div>
                            </div>
                            <div v-if="clients.length === 0 && !loading" class="empty-state">
                                {{ t("dsh.project.noApplications") }}
                            </div>
                        </div>
                    </div>

                    <!-- 应用详情弹窗（机器身份 = OIDC client 的完整信息） -->
                    <KsDialog
                        v-model="appDialogVisible"
                        :title="t('dsh.project.appDetail', {clientId: selectedApp?.clientId || ''})"
                        width="560"
                        class="app-detail-dialog"
                        destroy-on-close
                    >
                        <div v-if="selectedApp" class="app-detail">
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.clientId") }}</span>
                                <span class="detail-value">{{ selectedApp.clientId }}</span>
                            </div>
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.clientType") }}</span>
                                <span class="detail-value">
                                    <KsTag :type="isPublic(selectedApp) ? 'info' : 'warning'" size="small" effect="light">
                                        {{ isPublic(selectedApp) ? t("dsh.project.public") : t("dsh.project.confidential") }}
                                    </KsTag>
                                </span>
                            </div>
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.status") }}</span>
                                <span class="detail-value">
                                    <KsTag :type="selectedApp.active === false ? 'danger' : 'success'" size="small" effect="light">
                                        {{ selectedApp.active === false ? t("dsh.project.inactive") : t("dsh.project.active") }}
                                    </KsTag>
                                </span>
                            </div>
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.grantTypes") }}</span>
                                <span class="detail-value">
                                    <span v-for="g in (selectedApp.grantTypes || [])" :key="g" class="grant-tag">{{ g }}</span>
                                    <span v-if="!selectedApp.grantTypes || selectedApp.grantTypes.length === 0" class="detail-empty">—</span>
                                </span>
                            </div>
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.roles") }}</span>
                                <span class="detail-value">
                                    <KsTag
                                        v-for="r in (selectedApp.roles || [])"
                                        :key="r"
                                        :type="roleTagType(r)"
                                        size="small"
                                        effect="light"
                                        class="role-tag"
                                    >
                                        {{ r }}
                                    </KsTag>
                                    <span v-if="!selectedApp.roles || selectedApp.roles.length === 0" class="detail-empty">—</span>
                                </span>
                            </div>
                            <div class="detail-row">
                                <span class="detail-label">{{ t("dsh.project.scopes") }}</span>
                                <span class="detail-value">
                                    <span v-for="s in (selectedApp.scopes || [])" :key="s" class="grant-tag">{{ s }}</span>
                                    <span v-if="!selectedApp.scopes || selectedApp.scopes.length === 0" class="detail-empty">—</span>
                                </span>
                            </div>
                            <div class="detail-row" v-if="selectedApp.redirectUris && selectedApp.redirectUris.length > 0">
                                <span class="detail-label">{{ t("dsh.project.redirectUris") }}</span>
                                <span class="detail-value redirect-list">
                                    <div v-for="u in selectedApp.redirectUris" :key="u" class="redirect-item">{{ u }}</div>
                                </span>
                            </div>
                        </div>
                    </KsDialog>
                </div>
            </KsTabPane>

            <!-- 角色 Tab -->
            <KsTabPane :label="t('dsh.project.roles')" name="roles">
                <div class="tab-content">
                    <KsTable :data="roles" v-loading="loading" class="roles-table" :fit="true">
                        <KsTableColumn prop="roleName" :label="t('dsh.project.roleName')" min-width="150">
                            <template #default="{row}">
                                <KsTag :type="roleTagType(row.roleName)" size="small" effect="light">
                                    {{ row.roleName }}
                                </KsTag>
                            </template>
                        </KsTableColumn>
                        <KsTableColumn :label="t('dsh.project.description')" min-width="300">
                            <template #default="{row}">
                                {{ roleDescription(row.roleName, row.description) }}
                            </template>
                        </KsTableColumn>
                        <KsTableColumn prop="memberCount" :label="t('dsh.project.memberCount')" width="100" align="center">
                            <template #default="{row}">
                                <KsButton size="small" type="default" @click="selectRole(row.roleName)">
                                    {{ row.memberCount }}
                                </KsButton>
                            </template>
                        </KsTableColumn>
                        <KsTableColumn :label="t('actions')" width="200">
                            <template #default="{row}">
                                <div class="role-actions">
                                    <KsButton size="small" type="default" @click="selectRole(row.roleName)">
                                        {{ t("dsh.project.viewMembers") }}
                                    </KsButton>
                                    <KsButton size="small" type="success" @click="addMemberToRole(row.roleName)">
                                        <template #icon><AccountPlus /></template>
                                        {{ t("dsh.project.addMember") }}
                                    </KsButton>
                                </div>
                            </template>
                        </KsTableColumn>
                    </KsTable>

                    <!-- 选中角色的成员管理 -->
                    <div v-if="selectedRole" class="role-members-section">
                        <div class="members-header">
                            <h3 class="section-title">{{ t("dsh.roles.members", {role: selectedRole}) }}</h3>
                            <KsSelect
                                v-model="memberToAdd"
                                filterable
                                :placeholder="t('dsh.roles.addMemberPlaceholder')"
                                class="member-add-select"
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
                        <KsTable :data="members" class="member-table" :fit="true">
                            <KsTableColumn prop="username" :label="t('dsh.users.username')" min-width="180">
                                <template #default="{row}">
                                    <b>{{ row.username }}</b>
                                </template>
                            </KsTableColumn>
                            <KsTableColumn prop="name" :label="t('dsh.users.name')" min-width="120" />
                            <KsTableColumn prop="email" :label="t('dsh.users.email')" min-width="180" />
                            <KsTableColumn prop="type" :label="t('dsh.users.type')" width="120">
                                <template #default="{row}">
                                    <KsTag :type="row.type === 'machine' ? 'info' : 'primary'" size="small" effect="light">
                                        {{ row.type === 'machine' ? t('dsh.users.machine') : t('dsh.users.human') }}
                                    </KsTag>
                                </template>
                            </KsTableColumn>
                            <KsTableColumn :label="t('actions')" width="100">
                                <template #default="{row}">
                                    <KsButton size="small" type="danger" link @click="confirmRemove(row)">
                                        {{ t("dsh.roles.remove") }}
                                    </KsButton>
                                </template>
                            </KsTableColumn>
                        </KsTable>
                        <div v-if="members.length === 0" class="empty-state">{{ t("dsh.project.noMembers") }}</div>
                    </div>
                </div>
            </KsTabPane>
        </KsTabs>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {useRoute} from "vue-router"
    import {ElMessage, ElMessageBox} from "element-plus"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {getCsrfToken} from "../../../utils/csrf"
    import {SessionExpiredError, sessionExpired} from "../../../utils/dshSession"
    import AccountPlus from "vue-material-design-icons/AccountPlus.vue"

    interface UserRow {
        username: string;
        name: string;
        email: string;
        userState: string;
        roles: string[];
        type: string;
    }

    interface RoleRow {
        roleName: string;
        description: string;
        memberCount: number;
    }

    interface ClientRow {
        clientId: string;
        clientSecret: string;
        redirectUris: string[];
        grantTypes: string[];
        scopes: string[];
        projectId: string;
        roles: string[];
        active: boolean;
    }

    const {t, te} = useI18n({useScope: "global"})
    const route = useRoute()
    const routeInfo = computed(() => ({title: t("dsh.project.title")}))
    useRouteContext(routeInfo)

    const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin

    // 项目信息（当前只有 dsh，未来多项目时从 API 获取）
    const projectId = computed(() => (route.params.id as string) || "dsh")
    const projectName = computed(() => projectId.value)
    const projectDesc = computed(() => t("dsh.project.projectDescription"))
    const projectCreatedAt = "2026-09-03"

    const activeTab = ref("summary")
    const loading = ref(false)
    const users = ref<UserRow[]>([])
    const roles = ref<RoleRow[]>([])
    const clients = ref<ClientRow[]>([])
    const selectedRole = ref("")
    const memberToAdd = ref("")
    const appDialogVisible = ref(false)
    const selectedApp = ref<ClientRow | null>(null)

    const members = computed(() =>
        users.value.filter((u) => (u.roles || []).includes(selectedRole.value)),
    )

    const nonMembers = computed(() =>
        users.value.filter((u) => !(u.roles || []).includes(selectedRole.value) && u.userState === "ACTIVE"),
    )

    function onTabChange() {
        selectedRole.value = ""
        memberToAdd.value = ""
    }

    function selectRole(roleName: string) {
        selectedRole.value = roleName
    }

    // 直接从角色表格的"添加用户"按钮进入：展开成员管理区域
    function addMemberToRole(roleName: string) {
        selectedRole.value = roleName
        // 延迟聚焦到添加用户的下拉框
        setTimeout(() => {
            const select = document.querySelector(".member-add-select .el-select__input") as HTMLInputElement
            if (select) select.focus()
        }, 100)
    }

    function isPublic(client: ClientRow) {
        return !client.clientSecret || client.clientSecret === ""
    }

    // 点击应用卡片 → 打开应用详情弹窗（展示机器身份/OIDC client 完整信息）
    function openAppDetail(app: ClientRow) {
        selectedApp.value = app
        appDialogVisible.value = true
    }

    function roleTagType(role: string) {
        if (role === "admin") return "danger"
        if (role === "authenticated") return "info"
        return "primary"
    }

    /** Returns the localized role description; falls back to the API-provided description. */
    function roleDescription(roleName: string, fallback: string) {
        const key = `dsh.roles.descriptions.${roleName}`
        return te(key) ? t(key) : fallback
    }

    async function api(url: string, options: RequestInit = {}) {
        const headers = new Headers(options.headers)
        headers.set("Content-Type", "application/json")
        const csrf = getCsrfToken()
        if (csrf) headers.set("X-CSRF-TOKEN", csrf)
        const res = await fetch(`${API_BASE}${url}`, {
            credentials: "include",
            headers,
            ...options,
        })
        if (res.status === 401) {
            sessionExpired()
        }
        return res
    }

    async function load() {
        loading.value = true
        try {
            const [usersRes, rolesRes, clientsRes] = await Promise.all([
                api("/api/v1/oidc/users?size=500"),
                api("/api/v1/oidc/users/roles"),
                api("/api/v1/oidc/users/clients"),
            ])
            if (usersRes.status === 403 || rolesRes.status === 403 || clientsRes.status === 403) {
                ElMessage.error(t("dsh.users.notAdmin"))
                return
            }
            if (!usersRes.ok) throw new Error(await usersRes.text())
            if (!rolesRes.ok) throw new Error(await rolesRes.text())
            if (!clientsRes.ok) throw new Error(await clientsRes.text())
            users.value = await usersRes.json()
            roles.value = await rolesRes.json()
            clients.value = await clientsRes.json()
        } catch (e) {
            if (e instanceof SessionExpiredError) return
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
    .project-detail {
        padding: var(--ks-spacing-4);
    }

    .project-breadcrumb {
        display: flex;
        align-items: center;
        gap: var(--ks-spacing-2);
        margin-bottom: var(--ks-spacing-3);
        font-size: 0.875rem;
    }

    .breadcrumb-link {
        color: var(--ks-color-primary);
        text-decoration: none;

        &:hover {
            text-decoration: underline;
        }
    }

    .breadcrumb-separator {
        color: var(--ks-text-muted);
    }

    .breadcrumb-current {
        color: var(--ks-text-secondary);
        font-weight: 500;
    }

    .project-header {
        margin-bottom: var(--ks-spacing-4);
    }

    .project-title-row {
        display: flex;
        align-items: center;
        gap: var(--ks-spacing-3);
        margin-bottom: var(--ks-spacing-2);
    }

    .project-name {
        font-size: 1.5rem;
        font-weight: 700;
        margin: 0;
    }

    .project-desc {
        color: var(--ks-text-muted);
        margin: 0;
        font-size: 0.9rem;
    }

    .project-tabs {
        :deep(.el-tabs__header) {
            margin-bottom: var(--ks-spacing-4);
        }
    }

    .tab-content {
        min-height: 400px;
    }

    .section-title {
        font-size: 1.1rem;
        font-weight: 600;
        margin: 0 0 var(--ks-spacing-3) 0;
    }

    .info-section {
        margin-bottom: var(--ks-spacing-6);
    }

    .info-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--ks-spacing-4);
    }

    .info-item {
        display: flex;
        flex-direction: column;
        gap: var(--ks-spacing-1);
        padding: var(--ks-spacing-4);
        background: var(--ks-bg-surface);
        border: 1px solid var(--ks-border-default);
        border-radius: var(--ks-radius-base);
    }

    .info-label {
        font-size: 0.8rem;
        color: var(--ks-text-muted);
        text-transform: uppercase;
        letter-spacing: 0.05em;
    }

    .info-value {
        font-size: 1.25rem;
        font-weight: 600;
    }

    .apps-section {
        margin-bottom: var(--ks-spacing-4);
    }

    .apps-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
        gap: var(--ks-spacing-4);
    }

    .app-card {
        display: flex;
        gap: var(--ks-spacing-3);
        padding: var(--ks-spacing-4);
        border: 1px solid var(--ks-border-default);
        border-radius: var(--ks-radius-base);
        background: var(--ks-bg-surface);
        cursor: pointer;
        transition: box-shadow 0.2s, border-color 0.2s;

        &:hover {
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
            border-color: var(--ks-border-focus);
        }

        &.inactive {
            opacity: 0.6;
        }
    }

    .app-icon {
        width: 48px;
        height: 48px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.3rem;
        font-weight: 700;
        color: white;
        flex-shrink: 0;

        &.confidential {
            background: linear-gradient(135deg, #667eea, #764ba2);
        }

        &.public {
            background: linear-gradient(135deg, #11998e, #38ef7d);
        }
    }

    .app-info {
        flex: 1;
        min-width: 0;
    }

    .app-name {
        font-weight: 600;
        font-size: 1rem;
        margin-bottom: var(--ks-spacing-1);
        word-break: break-all;
    }

    .app-type {
        margin-bottom: var(--ks-spacing-2);
    }

    .app-grants {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
    }

    .grant-tag {
        font-size: 0.7rem;
        padding: 2px 6px;
        background: var(--ks-background-secondary);
        border-radius: 4px;
        color: var(--ks-text-secondary);
    }

    .roles-table,
    .member-table {
        margin-bottom: var(--ks-spacing-4);

        :deep(.kel-table) {
            overflow-x: auto !important;
        }
    }

    .role-actions {
        display: flex;
        gap: var(--ks-spacing-2);
        align-items: center;
    }

    .role-members-section {
        margin-top: var(--ks-spacing-6);
        padding-top: var(--ks-spacing-4);
        border-top: 1px solid var(--ks-border-color);
    }

    .members-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--ks-spacing-3);
    }

    .member-add-select {
        width: 300px;
    }

    .role-tag {
        margin-right: 4px;
    }

    .empty-state {
        text-align: center;
        padding: var(--ks-spacing-8);
        color: var(--ks-text-muted);
    }

    .app-inactive-tag {
        margin-top: 4px;
    }

    .app-detail {
        display: flex;
        flex-direction: column;
        gap: var(--ks-spacing-3);
    }

    .detail-row {
        display: flex;
        gap: var(--ks-spacing-3);
        align-items: flex-start;
    }

    .detail-label {
        width: 120px;
        flex-shrink: 0;
        font-size: 0.85rem;
        color: var(--ks-text-muted);
        padding-top: 4px;
    }

    .detail-value {
        flex: 1;
        font-size: 0.9rem;
        word-break: break-all;
    }

    .detail-empty {
        color: var(--ks-text-muted);
    }

    .redirect-list {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .redirect-item {
        font-family: var(--ks-font-family-mono, monospace);
        font-size: 0.8rem;
        background: var(--ks-bg-tag);
        border-radius: 4px;
        padding: 2px 6px;
    }
</style>
