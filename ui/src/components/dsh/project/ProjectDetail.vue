<template>
    <TopNavBar :title="routeInfo.title" />

    <section class="full-container project-detail">
        <!-- 项目头部 -->
        <div class="project-header">
            <div class="project-title-row">
                <h1 class="project-name">{{ projectName }}</h1>
                <KsTag type="success" size="small" effect="light">Default Project</KsTag>
            </div>
            <p class="project-desc">{{ projectDesc }}</p>
        </div>

        <!-- Tab 导航 -->
        <KsTabs v-model="activeTab" class="project-tabs" type="box">
            <!-- 概览 Tab -->
            <KsTabPane :label="t('dsh.project.overview')" name="summary">
                <div class="tab-content">
                    <!-- 项目信息 -->
                    <div class="info-section">
                        <h3 class="section-title">{{ t("dsh.project.projectInfo") }}</h3>
                        <div class="info-grid">
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.name") }}</span>
                                <span class="info-value">{{ projectName }}</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.description") }}</span>
                                <span class="info-value">{{ projectDesc }}</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">{{ t("dsh.project.createdAt") }}</span>
                                <span class="info-value">{{ projectCreatedAt }}</span>
                            </div>
                        </div>
                    </div>

                    <!-- Applications 卡片网格 -->
                    <div class="apps-section">
                        <h3 class="section-title">{{ t("dsh.project.applications") }}</h3>
                        <div class="apps-grid">
                            <div v-for="app in clients" :key="app.clientId" class="app-card">
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
                                </div>
                            </div>
                            <div v-if="clients.length === 0 && !loading" class="empty-state">
                                No applications
                            </div>
                        </div>
                    </div>
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
                        <KsTableColumn prop="description" :label="t('dsh.project.description')" min-width="300" />
                        <KsTableColumn prop="memberCount" :label="t('dsh.project.memberCount')" width="100" align="center" />
                        <KsTableColumn :label="t('actions')" width="150">
                            <template #default="{row}">
                                <KsButton size="small" type="primary" link @click="selectRole(row.roleName)">
                                    {{ t("dsh.project.viewMembers") }}
                                </KsButton>
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

            <!-- 角色分配 Tab -->
            <KsTabPane :label="t('dsh.project.assignments')" name="assignments">
                <div class="tab-content">
                    <KsTable :data="assignments" v-loading="loading" class="assignments-table" :fit="true">
                        <KsTableColumn prop="username" :label="t('dsh.project.user')" min-width="200">
                            <template #default="{row}">
                                <b>{{ row.username }}</b>
                            </template>
                        </KsTableColumn>
                        <KsTableColumn prop="name" :label="t('dsh.users.name')" min-width="120" />
                        <KsTableColumn prop="type" :label="t('dsh.users.type')" width="120">
                            <template #default="{row}">
                                <KsTag :type="row.type === 'machine' ? 'info' : 'primary'" size="small" effect="light">
                                    {{ row.type === 'machine' ? t('dsh.users.machine') : t('dsh.users.human') }}
                                </KsTag>
                            </template>
                        </KsTableColumn>
                        <KsTableColumn :label="t('dsh.project.role')" min-width="200">
                            <template #default="{row}">
                                <KsTag
                                    v-for="r in row.roles"
                                    :key="r"
                                    :type="roleTagType(r)"
                                    size="small"
                                    effect="light"
                                    class="role-tag"
                                >
                                    {{ r }}
                                </KsTag>
                            </template>
                        </KsTableColumn>
                    </KsTable>
                </div>
            </KsTabPane>
        </KsTabs>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {ElMessage, ElMessageBox} from "element-plus"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"
    import {getCsrfToken} from "../../../utils/csrf"
    import {SessionExpiredError, sessionExpired} from "../../../utils/dshSession"

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
    }

    const {t} = useI18n({useScope: "global"})
    const routeInfo = computed(() => ({title: t("dsh.project.title")}))
    useRouteContext(routeInfo)

    const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin

    // 默认 dsh 项目信息
    const projectName = "dsh"
    const projectDesc = "DSH Ecosystem — Default project containing all dsh applications: Kestra, Nacos, dsh PC/mobile"
    const projectCreatedAt = "2026-09-03"

    const activeTab = ref("summary")
    const loading = ref(false)
    const users = ref<UserRow[]>([])
    const roles = ref<RoleRow[]>([])
    const clients = ref<ClientRow[]>([])
    const selectedRole = ref("")
    const memberToAdd = ref("")

    const members = computed(() =>
        users.value.filter((u) => (u.roles || []).includes(selectedRole.value)),
    )

    const nonMembers = computed(() =>
        users.value.filter((u) => !(u.roles || []).includes(selectedRole.value) && u.userState === "ACTIVE"),
    )

    const assignments = computed(() => users.value)

    function onTabChange() {
        selectedRole.value = ""
        memberToAdd.value = ""
    }

    function selectRole(roleName: string) {
        selectedRole.value = roleName
    }

    function isPublic(client: ClientRow) {
        return !client.clientSecret || client.clientSecret === ""
    }

    function roleTagType(role: string) {
        if (role === "admin") return "danger"
        if (role === "authenticated") return "info"
        return "primary"
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
        grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
        gap: var(--ks-spacing-4);
    }

    .info-item {
        display: flex;
        flex-direction: column;
        gap: var(--ks-spacing-1);
    }

    .info-label {
        font-size: 0.8rem;
        color: var(--ks-text-muted);
        text-transform: uppercase;
        letter-spacing: 0.05em;
    }

    .info-value {
        font-size: 0.95rem;
        font-weight: 500;
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
        border: 1px solid var(--ks-border-color);
        border-radius: var(--ks-radius-sm);
        background: var(--ks-background-primary);
        transition: box-shadow 0.2s;

        &:hover {
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
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
    .member-table,
    .assignments-table {
        margin-bottom: var(--ks-spacing-4);

        :deep(.kel-table) {
            overflow-x: auto !important;
        }
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
</style>
