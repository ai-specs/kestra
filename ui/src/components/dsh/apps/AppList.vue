<template>
    <div class="dsh-app-list">
        <h1 class="dsh-app-list__title">{{ t("apps") }}</h1>
        <p class="dsh-app-list__hint">
            声明了 <code>PageTrigger</code> / <code>ApiTrigger</code> 的 flow 会出现在这里，点击进入对应的 flow 编辑器。
        </p>
        <div v-if="loading" class="dsh-app-list__status">Loading apps…</div>
        <div v-else-if="error" class="dsh-app-list__status dsh-app-list__error">{{ error }}</div>
        <div v-else-if="apps.length === 0" class="dsh-app-list__status">
            还没有应用程序。在 flow 的 triggers 中声明 <code>io.kestra.plugin.dsh.apps.PageTrigger</code> 即可创建一个 App。
        </div>
        <div v-else class="dsh-app-list__table">
            <div class="dsh-app-list__row dsh-app-list__row--head">
                <span class="dsh-app-list__col dsh-app-list__col--flow">Flow</span>
                <span class="dsh-app-list__col dsh-app-list__col--ns">Namespace</span>
                <span class="dsh-app-list__col dsh-app-list__col--pages">页面</span>
                <span class="dsh-app-list__col dsh-app-list__col--url">页面 URL</span>
                <span class="dsh-app-list__col dsh-app-list__col--apis">API</span>
            </div>
            <div
                v-for="app in apps"
                :key="`${app.namespace}/${app.flowId}`"
                class="dsh-app-list__row"
            >
                <span class="dsh-app-list__col dsh-app-list__col--flow">
                    <a
                        class="dsh-app-list__flow-link"
                        :href="`${basePath}/flows/edit/${encodeURIComponent(app.namespace)}/${encodeURIComponent(app.flowId)}/edit`"
                    >{{ app.flowId }}</a>
                    <span class="dsh-app-list__app-name">(app: {{ app.appName }})</span>
                </span>
                <span class="dsh-app-list__col dsh-app-list__col--ns">{{ app.namespace }}</span>
                <span class="dsh-app-list__col dsh-app-list__col--pages">
                    <span v-for="p in app.pages" :key="p" class="dsh-app-list__tag">{{ p }}</span>
                    <span v-if="app.pages.length === 0" class="dsh-app-list__muted">—</span>
                </span>
                <span class="dsh-app-list__col dsh-app-list__col--url">
                    <span v-if="app.pages.length > 0" class="dsh-app-list__urls">
                        <a
                            v-for="p in app.pages"
                            :key="p"
                            class="dsh-app-list__url"
                            :href="`/apps/${encodeURIComponent(app.appName)}/${encodeURIComponent(p)}`"
                            target="_blank"
                            rel="noopener"
                        >/apps/{{ app.appName }}/{{ p }}</a>
                    </span>
                    <span v-else class="dsh-app-list__muted">—</span>
                </span>
                <span class="dsh-app-list__col dsh-app-list__col--apis">
                    <span v-for="a in app.apis" :key="a" class="dsh-app-list__tag">{{ a }}</span>
                    <span v-if="app.apis.length === 0" class="dsh-app-list__muted">—</span>
                </span>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue";
    import {useI18n} from "vue-i18n";
    import {apiUrlWithoutTenants} from "override/utils/route";

    interface AppSummary {
        appName: string;
        namespace: string;
        flowId: string;
        pages: string[];
        apis: string[];
    }

    const {t} = useI18n({useScope: "global"});

    const apps = ref<AppSummary[]>([]);
    const loading = ref(true);
    const error = ref("");

    const basePath = computed(() => {
        const tenant = window.location.pathname.match(/^\/ui\/([^/]+)/)?.[1];
        return tenant ? `/ui/${tenant}` : "/ui/main";
    });

    async function load() {
        loading.value = true;
        error.value = "";
        try {
            const resp = await fetch(`${apiUrlWithoutTenants()}/apps`, {
                headers: {"Accept": "application/json"},
                credentials: "include",
            });
            if (!resp.ok) {
                throw new Error(`List apps returned HTTP ${resp.status}`);
            }
            apps.value = (await resp.json()) as AppSummary[];
        } catch (e) {
            error.value = `Failed to load apps: ${(e as Error).message ?? e}`;
        } finally {
            loading.value = false;
        }
    }

    onMounted(load);
</script>

<style scoped>
    .dsh-app-list {
        padding: 24px;
    }
    .dsh-app-list__title {
        font-size: 20px;
        font-weight: 600;
        margin-bottom: 4px;
    }
    .dsh-app-list__hint {
        font-size: 13px;
        color: var(--bs-secondary-color);
        margin-bottom: 16px;
    }
    .dsh-app-list__status {
        color: var(--bs-body-color);
        font-size: 14px;
        padding: 12px 0;
    }
    .dsh-app-list__error {
        color: var(--bs-danger);
    }
    .dsh-app-list__table {
        border: 1px solid var(--bs-border-color);
        border-radius: 8px;
        overflow: hidden;
    }
    .dsh-app-list__row {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 10px 16px;
        border-bottom: 1px solid var(--bs-border-color);
        background: #fff;
    }
    .dsh-app-list__row:last-child {
        border-bottom: none;
    }
    .dsh-app-list__row--head {
        background: var(--bs-tertiary-bg, #f8f9fa);
        font-size: 12px;
        color: var(--bs-secondary-color);
        text-transform: uppercase;
    }
    .dsh-app-list__col--flow {
        flex: 2;
        min-width: 0;
    }
    .dsh-app-list__col--ns {
        flex: 1;
        min-width: 0;
    }
    .dsh-app-list__col--pages,
    .dsh-app-list__col--url,
    .dsh-app-list__col--apis {
        flex: 1.2;
        min-width: 0;
    }
    .dsh-app-list__urls {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }
    .dsh-app-list__url {
        font-family: ui-monospace, monospace;
        font-size: 12px;
        color: var(--bs-link-color);
        text-decoration: none;
        word-break: break-all;
    }
    .dsh-app-list__url:hover {
        text-decoration: underline;
    }
    .dsh-app-list__flow-link {
        color: var(--bs-link-color);
        text-decoration: none;
        font-weight: 600;
        word-break: break-all;
    }
    .dsh-app-list__flow-link:hover {
        text-decoration: underline;
    }
    .dsh-app-list__app-name {
        font-size: 12px;
        color: var(--bs-secondary-color);
        margin-left: 6px;
        white-space: nowrap;
    }
    .dsh-app-list__tag {
        display: inline-block;
        margin: 2px 6px 2px 0;
        padding: 1px 8px;
        border-radius: 10px;
        background: var(--bs-tertiary-bg, #f0f2f5);
        font-family: ui-monospace, monospace;
        font-size: 12px;
        color: var(--bs-body-color);
        word-break: break-all;
    }
    .dsh-app-list__muted {
        color: var(--bs-secondary-color);
    }
</style>
