<template>
    <div class="dsh-app-list">
        <h1 class="dsh-app-list__title">{{ t("apps") }}</h1>
        <p class="dsh-app-list__hint">
            声明了 <code>PageTrigger</code> / <code>ApiTrigger</code> 的 flow 会出现在这里，点击进入对应的 flow 编辑器。
        </p>
        <div v-if="error" class="dsh-app-list__error">{{ error }}</div>
        <KsDataTable
            ref="dataTable"
            :loadData="loadData"
            :data="apps"
            :total="apps.length"
            :defaultSort="{prop: 'flowId', order: 'ascending'}"
            :selectable="false"
            :no-data-text="'还没有应用程序。在 flow 的 triggers 中声明 io.kestra.plugin.dsh.apps.PageTrigger 即可创建一个 App。'"
            :fitHeight="false"
            :rowKey="(row: any) => `${row.namespace}-${row.flowId}`"
        >
            <KsTableColumn prop="flowId" :label="$t('flow')" sortable="custom" :sortOrders="['ascending', 'descending']">
                <template #default="scope">
                    <a
                        class="dsh-app-list__flow-link"
                        :href="`${basePath}/flows/edit/${encodeURIComponent(scope.row.namespace)}/${encodeURIComponent(scope.row.flowId)}/edit`"
                    >{{ scope.row.flowId }}</a>
                    <span class="dsh-app-list__app-name">(app: {{ scope.row.appName }})</span>
                </template>
            </KsTableColumn>

            <KsTableColumn prop="namespace" :label="$t('namespace')" sortable="custom" :sortOrders="['ascending', 'descending']">
                <template #default="scope">{{ scope.row.namespace }}</template>
            </KsTableColumn>

            <KsTableColumn :label="'页面'">
                <template #default="scope">
                    <KsTag
                        v-for="p in scope.row.pages"
                        :key="p"
                        size="small"
                        type="info"
                        effect="light"
                        class="dsh-app-list__tag"
                    >{{ p }}</KsTag>
                    <span v-if="scope.row.pages.length === 0" class="dsh-app-list__muted">—</span>
                </template>
            </KsTableColumn>

            <KsTableColumn :label="'页面 URL'">
                <template #default="scope">
                    <div v-if="scope.row.pages.length > 0" class="dsh-app-list__urls">
                        <span
                            v-for="p in scope.row.pages"
                            :key="p"
                            class="dsh-app-list__url-row"
                        >
                            <a
                                class="dsh-app-list__url"
                                :href="`/${encodeURIComponent(scope.row.namespace)}/${encodeURIComponent(scope.row.appName)}/${encodeURIComponent(p)}`"
                                target="_blank"
                                rel="noopener"
                            >{{ scope.row.namespace }}/{{ scope.row.appName }}/{{ p }}</a>
                            <!-- dsh fork: 单元格内「设计」直达全屏编辑器（hash 携带 namespace + 文件路径，
                                 任意存在的 json 文件都可编辑；约定外页面 404 报错不建空页） -->
                            <a
                                class="dsh-app-list__design"
                                :href="`/apps/pages-edit#${encodeURIComponent(scope.row.namespace)}/apps/${encodeURIComponent(scope.row.appName)}/${encodeURIComponent(p)}.json`"
                                target="_blank"
                                rel="noopener"
                            >设计</a>
                        </span>
                    </div>
                    <span v-else class="dsh-app-list__muted">—</span>
                </template>
            </KsTableColumn>

            <KsTableColumn :label="'API'">
                <template #default="scope">
                    <KsTag
                        v-for="a in scope.row.apis"
                        :key="a"
                        size="small"
                        type="info"
                        effect="light"
                        class="dsh-app-list__tag"
                    >{{ a }}</KsTag>
                    <span v-if="scope.row.apis.length === 0" class="dsh-app-list__muted">—</span>
                </template>
            </KsTableColumn>

            <KsTableColumn columnKey="delete" className="row-action">
                <template #default="scope">
                    <KsIconButton
                        :tooltip="$t('delete')"
                        placement="left"
                        @click="removeApp(scope.row)"
                    >
                        <Delete />
                    </KsIconButton>
                </template>
            </KsTableColumn>
        </KsDataTable>
    </div>
</template>

<script setup lang="ts">
    import {computed, ref, useTemplateRef} from "vue";
    import {useI18n} from "vue-i18n";
    import {apiUrl, apiUrlWithoutTenants} from "override/utils/route";
    import {getCsrfToken} from "../../../utils/csrf";
    import {useToast} from "../../../utils/toast";
    import {KsIconButton, KsTag} from "@kestra-io/design-system";
    import Delete from "vue-material-design-icons/Delete.vue";

    interface AppSummary {
        appName: string;
        namespace: string;
        flowId: string;
        pages: string[];
        apis: string[];
    }

    const {t} = useI18n({useScope: "global"});

    const apps = ref<AppSummary[]>([]);
    const error = ref("");
    const dataTable = useTemplateRef("dataTable");
    const toast = useToast();

    const basePath = computed(() => {
        const tenant = window.location.pathname.match(/^\/ui\/([^/]+)/)?.[1];
        return tenant ? `/ui/${tenant}` : "/ui/main";
    });

    async function loadData() {
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
            return apps.value;
        } catch (e) {
            error.value = `Failed to load apps: ${(e as Error).message ?? e}`;
            throw e;
        }
    }

    function removeApp(app: AppSummary) {
        toast.confirm(
            t("delete") + ` "${app.flowId}"（app: ${app.appName}）？此操作会同时删除它的页面与 API 路由。`,
            () => {
                const url = `${apiUrl()}/flows/${encodeURIComponent(app.namespace)}/${encodeURIComponent(app.flowId)}`;
                return fetch(url, {
                    method: "DELETE",
                    headers: {
                        "X-CSRF-TOKEN": getCsrfToken() ?? "",
                        "Accept": "application/json",
                    },
                    credentials: "include",
                })
                    .then((resp) => {
                        if (!resp.ok) {
                            return resp.json().catch(() => null).then((body) => {
                                const msg = body?.message ?? `HTTP ${resp.status}`;
                                throw new Error(`Delete flow failed: ${msg}`);
                            });
                        }
                    })
                    .then(() => {
                        toast.success("App 已删除");
                        dataTable.value?.reload();
                    })
                    .catch((e) => {
                        toast.error(`Delete flow failed: ${(e as Error).message ?? e}`);
                    });
            },
        );
    }
</script>

<style scoped>
    .dsh-app-list {
        padding: 24px;
    }
    .dsh-app-list__title {
        font-size: 20px;
        font-weight: 600;
        margin-bottom: 4px;
        color: var(--bs-body-color);
    }
    .dsh-app-list__hint {
        font-size: 13px;
        color: var(--bs-secondary-color);
        margin-bottom: 16px;
    }
    .dsh-app-list__error {
        color: var(--bs-danger);
        font-size: 14px;
        padding: 12px 0;
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
    .dsh-app-list__urls {
        display: flex;
        flex-direction: column;
        gap: 2px;
    }
    .dsh-app-list__url-row {
        display: inline-flex;
        align-items: center;
        gap: 6px;
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
    .dsh-app-list__design {
        font-size: 12px;
        color: #1677ff;
        text-decoration: none;
        white-space: nowrap;
        padding: 0 4px;
        border: 1px solid #91caff;
        border-radius: 3px;
    }
    .dsh-app-list__design:hover {
        background: #e6f4ff;
    }
    .dsh-app-list__tag {
        margin: 2px 6px 2px 0;
    }
    .dsh-app-list__muted {
        color: var(--bs-secondary-color);
    }
</style>
