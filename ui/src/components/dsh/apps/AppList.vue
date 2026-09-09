<template>
    <div class="dsh-app-list">
        <h1 class="dsh-app-list__title">应用程序</h1>
        <div v-if="loading" class="dsh-app-list__status">Loading apps…</div>
        <div v-else-if="error" class="dsh-app-list__status dsh-app-list__error">{{ error }}</div>
        <div v-else-if="apps.length === 0" class="dsh-app-list__status">
            还没有应用程序。在 flow 的 triggers 中声明 <code>io.kestra.plugin.dsh.apps.PageTrigger</code> 即可创建一个 App。
        </div>
        <div v-else class="dsh-app-list__grid">
            <div v-for="app in apps" :key="app.appName" class="dsh-app-list__card">
                <div class="dsh-app-list__card-head">
                    <span class="dsh-app-list__card-name">{{ app.appName }}</span>
                    <span class="dsh-app-list__card-ns">{{ app.namespace }}</span>
                </div>
                <div v-if="app.pages.length > 0" class="dsh-app-list__section">
                    <div class="dsh-app-list__section-title">页面</div>
                    <a
                        v-for="page in app.pages"
                        :key="page"
                        class="dsh-app-list__link"
                        :href="`${basePath}/apps/${app.appName}/${page}`"
                    >{{ page }}</a>
                </div>
                <div v-if="app.apis.length > 0" class="dsh-app-list__section">
                    <div class="dsh-app-list__section-title">API</div>
                    <span v-for="api in app.apis" :key="api" class="dsh-app-list__api">{{ api }}</span>
                </div>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue";
    import {apiUrlWithoutTenants} from "override/utils/route";

    interface AppSummary {
        appName: string;
        namespace: string;
        pages: string[];
        apis: string[];
    }

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
    .dsh-app-list__grid {
        display: flex;
        flex-wrap: wrap;
        gap: 16px;
    }
    .dsh-app-list__card {
        flex: 1 1 280px;
        min-width: 0;
        border: 1px solid var(--bs-border-color);
        border-radius: 8px;
        padding: 16px;
        background: #fff;
    }
    .dsh-app-list__card-head {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 8px;
        margin-bottom: 12px;
    }
    .dsh-app-list__card-name {
        font-size: 16px;
        font-weight: 600;
        word-break: break-all;
    }
    .dsh-app-list__card-ns {
        font-size: 12px;
        color: var(--bs-secondary-color);
        word-break: break-all;
    }
    .dsh-app-list__section {
        margin-bottom: 8px;
    }
    .dsh-app-list__section-title {
        font-size: 12px;
        color: var(--bs-secondary-color);
        margin-bottom: 4px;
    }
    .dsh-app-list__link {
        display: inline-block;
        margin: 2px 8px 2px 0;
        color: var(--bs-link-color);
        text-decoration: none;
    }
    .dsh-app-list__link:hover {
        text-decoration: underline;
    }
    .dsh-app-list__api {
        display: inline-block;
        margin: 2px 8px 2px 0;
        font-family: ui-monospace, monospace;
        font-size: 13px;
        color: var(--bs-body-color);
    }
</style>
