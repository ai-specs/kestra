<template>
    <div class="p-4">
        <div v-if="loading" class="pt-2">
            <KsSkeleton :rows="4" animated />
        </div>

        <span v-else-if="sessions.length === 0" class="text-muted">{{ $t("dsh.employee.no_sessions") }}</span>

        <KsTable v-else :data="sessions" :fit="true" class="session-table">
            <KsTableColumn prop="sessionId" label="sessionId" :minWidth="300">
                <template #default="{row}">
                    <code>{{ row.sessionId }}</code>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="$t('dsh.employee.file_count')" width="100" align="center">
                <template #default="{row}">
                    <span class="session-badge">{{ row.fileCount }}</span>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="$t('dsh.employee.uploads')" width="90" align="center">
                <template #default="{row}">
                    <span :class="row.hasUploads ? 'ok-mark' : 'text-muted'">{{ row.hasUploads ? "✓" : "—" }}</span>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="$t('dsh.employee.reply')" width="90" align="center">
                <template #default="{row}">
                    <span :class="row.hasReply ? 'ok-mark' : 'text-muted'">{{ row.hasReply ? "✓" : "—" }}</span>
                </template>
            </KsTableColumn>
            <KsTableColumn :label="$t('dsh.employee.stderr')" width="90" align="center">
                <template #default="{row}">
                    <span :class="row.hasStderr ? 'warn-mark' : 'text-muted'">{{ row.hasStderr ? "✓" : "—" }}</span>
                </template>
            </KsTableColumn>
            <KsTableColumn width="140" align="center">
                <template #default="{row}">
                    <KsButton
                        size="small"
                        @click.stop="browseFiles(row.sessionId)"
                    >{{ $t("dsh.employee.browse_files") }}</KsButton>
                </template>
            </KsTableColumn>
        </KsTable>
    </div>
</template>

<script setup lang="ts">
    import {onMounted, ref, watch} from "vue"
    import {useRoute, useRouter} from "vue-router"
    import {KsButton, KsSkeleton} from "@kestra-io/design-system"

    interface SessionRow {
        sessionId: string
        fileCount: number
        hasUploads: boolean
        hasReply: boolean
        hasStderr: boolean
    }

    const props = defineProps<{
        namespace: string
    }>()

    const route = useRoute()
    const router = useRouter()

    const loading = ref(false)
    const sessions = ref<SessionRow[]>([])

    const api = (path: string) => `/api/v1${route.params.tenant ? "/" + route.params.tenant : ""}${path}`

    const loadSessions = async () => {
        loading.value = true
        sessions.value = []
        try {
            const res = await fetch(api(`/dsh-employee/${encodeURIComponent(props.namespace)}/sessions`), {credentials: "include"})
            if (res.ok) {
                sessions.value = await res.json()
            }
        } finally {
            loading.value = false
        }
    }

    // 深链到文件 tab 并定位该会话目录（?path=/sessions/{id}，文件树展开并高亮）
    const browseFiles = (sessionId: string) => {
        router.push({
            name: "employees/update/files",
            params: {namespace: props.namespace},
            query: {path: `/sessions/${sessionId}`},
        })
    }

    watch(() => props.namespace, loadSessions)
    onMounted(loadSessions)
</script>

<style scoped>
    .text-muted {
        color: var(--ks-text-muted);
    }
    .session-badge {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 10px;
        font-size: 0.85em;
        background: var(--ks-bg-badge);
    }
    .ok-mark { color: var(--ks-text-success); }
    .warn-mark { color: var(--ks-text-warning); }
</style>
