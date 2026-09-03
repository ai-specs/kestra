<template>
    <TopNavBar :title="routeInfo.title">
        <template #actions>
            <ul>
                <li>
                    <KsButton :icon="Plus" type="primary" data-test="project-add" @click="onCreateProject">
                        {{ t("dsh.project.add") }}
                    </KsButton>
                </li>
            </ul>
        </template>
    </TopNavBar>

    <section class="full-container project-list">
        <div class="project-toolbar">
            <KsInput
                v-model="search"
                :placeholder="t('dsh.project.searchPlaceholder')"
                clearable
                class="project-search"
                @keyup.enter="load"
            />
            <KsButton :icon="Magnify" type="default" @click="load">{{ t("search") }}</KsButton>
        </div>

        <KsTable
            :data="filteredProjects"
            v-loading="loading"
            :fit="true"
            class="project-table"
            @row-click="onRowClick"
        >
            <KsTableColumn prop="name" :label="t('dsh.project.name')" min-width="160">
                <template #default="{row}">
                    <div class="project-name-cell">
                        <div class="project-icon" :class="row.id">
                            {{ row.name.charAt(0).toUpperCase() }}
                        </div>
                        <div class="project-name-text">
                            <b>{{ row.name }}</b>
                            <KsTag type="success" size="small" effect="light" class="project-type-tag">
                                {{ t("dsh.project.defaultProject") }}
                            </KsTag>
                        </div>
                    </div>
                </template>
            </KsTableColumn>
            <KsTableColumn prop="description" :label="t('dsh.project.description')" min-width="280" />
            <KsTableColumn :label="t('dsh.project.applications')" width="100" align="center">
                <template #default="{row}">
                    <b>{{ row.applicationCount }}</b>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.project.roles')" width="100" align="center">
                <template #default="{row}">
                    <b>{{ row.roleCount }}</b>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('dsh.project.createdAt')" width="140">
                <template #default="{row}">
                    <KsDateAgo :date="row.createdAt" inverted />
                </template>
            </KsTableColumn>
            <KsTableColumn :label="t('actions')" width="120">
                <template #default="{row}">
                    <KsButton size="small" type="primary" link @click.stop="goToDetail(row)">
                        {{ t("dsh.project.view") }}
                    </KsButton>
                </template>
            </KsTableColumn>
        </KsTable>
    </section>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useI18n} from "vue-i18n"
    import {useRouter} from "vue-router"
    import {ElMessage} from "element-plus"
    import Plus from "vue-material-design-icons/Plus.vue"
    import Magnify from "vue-material-design-icons/Magnify.vue"
    import TopNavBar from "../../layout/TopNavBar.vue"
    import useRouteContext from "../../../composables/useRouteContext"

    interface ProjectRow {
        id: string;
        name: string;
        description: string;
        applicationCount: number;
        roleCount: number;
        createdAt: string;
    }

    const {t} = useI18n({useScope: "global"})
    const router = useRouter()
    const routeInfo = computed(() => ({title: t("dsh.project.title")}))
    useRouteContext(routeInfo)

    const search = ref("")
    const loading = ref(false)
    const projects = ref<ProjectRow[]>([])

    // 当前硬编码默认项目 dsh；未来多项目时从 GET /api/v1/oidc/projects 获取
    const defaultProjects: ProjectRow[] = [
        {
            id: "dsh",
            name: "dsh",
            description: "",
            applicationCount: 5,
            roleCount: 3,
            createdAt: "2026-09-03T00:00:00Z",
        },
    ]

    const filteredProjects = computed(() => {
        if (!search.value) return projects.value
        const q = search.value.toLowerCase()
        return projects.value.filter(
            (p) => p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q),
        )
    })

    async function load() {
        loading.value = true
        try {
            // 未来：从 API 获取项目列表
            // const res = await fetch("/api/v1/oidc/projects")
            // projects.value = await res.json()
            projects.value = defaultProjects.map((p) => ({
                ...p,
                description: t("dsh.project.projectDescription"),
            }))
        } finally {
            loading.value = false
        }
    }

    function onRowClick(row: ProjectRow) {
        goToDetail(row)
    }

    function goToDetail(row: ProjectRow) {
        router.push({name: "admin/project/detail", params: {id: row.id}})
    }

    function onCreateProject() {
        ElMessage.info(t("dsh.project.createNotAvailable"))
    }

    onMounted(load)
</script>

<style scoped lang="scss">
    .project-list {
        padding: var(--ks-spacing-4);
    }

    .project-toolbar {
        display: flex;
        gap: var(--ks-spacing-2);
        margin-bottom: var(--ks-spacing-4);
        align-items: center;
        flex: 0 0 auto;
    }

    .project-search {
        width: 320px;
    }

    .project-table {
        :deep(.kel-table) {
            overflow-x: auto !important;
        }

        :deep(.el-table__row) {
            cursor: pointer;
        }
    }

    .project-name-cell {
        display: flex;
        align-items: center;
        gap: var(--ks-spacing-3);
    }

    .project-icon {
        width: 40px;
        height: 40px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 1.1rem;
        font-weight: 700;
        color: white;
        flex-shrink: 0;
        background: linear-gradient(135deg, #667eea, #764ba2);
    }

    .project-name-text {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }

    .project-type-tag {
        width: fit-content;
    }
</style>
