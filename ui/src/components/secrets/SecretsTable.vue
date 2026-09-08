<template>
    <div class="secrets-table">
        <KsDataTable
            ref="dataTable"
            :loadData="loadData"
            :data="secrets"
            :total="total"
            :currentPage="urlPage"
            :pageSize="urlSize"
            :defaultSort="{prop: 'key', order: 'ascending'}"
            :selectable="false"
            @page-changed="({page, size}: {page: number; size: number}) => router.push({query: {...route.query, page: String(page), size: String(size)}})"
            @sort-change="({prop, order}: {column: any; prop: string | null; order: string | null}) => router.push({query: {...route.query, sort: `${prop}:${order === 'ascending' ? 'asc' : 'desc'}`}})"
            :no-data-text="$t('no_results.secrets')"
            :fitHeight="!paneView && !keyOnly"
            :rowKey="(row: any) => `${row.namespace}-${row.key}`"
        >
            <template v-if="$slots.empty && showEmptyState" #empty>
                <slot name="empty" />
            </template>

            <template #top v-if="!paneView">
                <KSFilter
                    :configuration="secretsFilter"
                    :tableOptions="{
                        chart: {shown: false},
                        refresh: {shown: true, callback: () => dataTable?.reload()}
                    }"
                    :prefix="'secrets'"
                    :buttons="{savedFilters: {shown: !namespace}}"
                    :properties="{
                        shown: true,
                        columns: optionalColumns,
                        displayColumns,
                        storageKey: storageKey
                    }"
                    @update-properties="updateDisplayColumns"
                />
            </template>

            <KsTableColumn
                prop="key"
                sortable="custom"
                :sortOrders="['ascending', 'descending']"
                :label="keyOnly ? $t('secret.names') : $t('key')"
            >
                <template #default="scope">
                    <KsId v-if="scope.row?.key !== undefined" :value="scope.row.key" :shrink="false" />
                </template>
            </KsTableColumn>

            <KsTableColumn
                v-for="col in visibleColumns"
                :key="col.prop"
                :prop="col.prop"
                :label="col.label"
                :sortable="col.prop === 'namespace' ? 'custom' : false"
                :sortOrders="col.prop === 'namespace' ? ['ascending', 'descending'] : []"
            >
                <template #default="scope">
                    <template v-if="col.prop === 'namespace'">
                        <KsEntityLink
                            v-if="scope.row?.namespace"
                            entity="namespace"
                            :value="scope.row.namespace"
                            :to="{name: 'namespaces/update', params: {id: scope.row.namespace}}"
                        />
                        <span v-else class="secret-global-namespace">{{ $t('secret.globalNamespace') }}</span>
                    </template>
                    <template v-else-if="col.prop === 'description'">
                        {{ scope.row?.description }}
                    </template>
                </template>
            </KsTableColumn>

            <KsTableColumn columnKey="locked" className="row-action">
                <template #default="scope">
                    <KsTooltip
                        v-if="scope.row?.namespace !== undefined && areNamespaceSecretsReadOnly"
                    >
                        <template #content>
                            <span v-html="$t('secret.isReadOnly')" />
                        </template>
                        <KsIcon class="d-flex justify-content-center">
                            <Lock />
                        </KsIcon>
                    </KsTooltip>
                </template>
            </KsTableColumn>

            <KsTableColumn columnKey="copy" className="row-action">
                <template #default="scope">
                    <KsIconButton
                        :tooltip="$t('copy_pebble_expression')"
                        placement="left"
                        @click="copyKey(scope.row?.key)"
                    >
                        <ContentCopy />
                    </KsIconButton>
                </template>
            </KsTableColumn>

            <KsTableColumn
                v-if="!keyOnly && !paneView"
                columnKey="update"
                className="row-action"
            >
                <template #default="scope">
                    <KsIconButton
                        v-if="canUpdate(scope.row)"
                        :tooltip="$t('update')"
                        placement="left"
                        @click="updateSecretModal(scope.row)"
                    >
                        <FileDocumentEdit />
                    </KsIconButton>
                </template>
            </KsTableColumn>

            <KsTableColumn
                v-if="!keyOnly && !paneView"
                columnKey="delete"
                className="row-action"
            >
                <template #default="scope">
                    <KsIconButton
                        v-if="canDelete(scope.row)"
                        :tooltip="$t('delete')"
                        placement="left"
                        @click="removeSecret(scope.row)"
                    >
                        <Delete />
                    </KsIconButton>
                </template>
            </KsTableColumn>
        </KsDataTable>

        <KsDialog
            v-if="addSecretDrawerVisible"
            v-model="addSecretDrawerVisible"
            :title="secretModalTitle"
            :beforeClose="beforeSecretClose"
            formLayout
            scrollable
        >
            <KsForm labelPosition="left" :model="secret" :rules="rules" ref="form">
                <KsFormItem
                    v-if="namespace === undefined"
                    :label="$t('namespace')"
                    prop="namespace"
                    required
                    inline
                    class="field-item"
                >
                    <NamespaceSelect
                        v-model="secret.namespace"
                        :readOnly="secret.update"
                        :includeSystemNamespace="true"
                        all
                    />
                </KsFormItem>
                <KsFormItem :label="$t('secret.key')" prop="key" required inline class="field-item">
                    <KsInput v-model="secret.key" :disabled="secret.update" :placeholder="$t('secret.keyPlaceholder')" required />
                </KsFormItem>
                <KsFormItem v-if="!secret.update" :label="$t('secret.name')" prop="value" required inline class="field-item">
                    <KsPassword v-model="secret.value" :placeholder="$t('secret.valuePlaceholder')" />
                </KsFormItem>
                <KsFormItem v-if="secret.update" :label="$t('secret.name')" prop="value" inline class="field-item">
                    <KsPassword
                        v-model="secret.value"
                        :placeholder="$t('secret.valuePlaceholderUpdate')"
                    />
                </KsFormItem>
                <KsFormItem :label="$t('secret.description')" prop="description" labelPosition="top">
                    <KsInput
                        v-model="secret.description"
                        :placeholder="$t('secret.descriptionPlaceholder')"
                        type="textarea"
                        :rows="2"
                        resize="vertical"
                    />
                </KsFormItem>
            </KsForm>

            <template #footer>
                <KsButton @click="addSecretDrawerVisible = false">
                    {{ $t('cancel') }}
                </KsButton>
                <KsButton :icon="ContentSave" @click="saveSecret(form)" type="primary">
                    {{ $t('save') }}
                </KsButton>
            </template>
        </KsDialog>
    </div>
</template>

<script setup lang="ts">
    import {useI18n} from "vue-i18n"
    import {useRoute, useRouter} from "vue-router"
    import type {FormInstance} from "@kestra-io/design-system"
    import {ref, computed, watch, nextTick, useTemplateRef} from "vue"
    import _merge from "lodash/merge"

    import Lock from "vue-material-design-icons/Lock.vue"
    import Delete from "vue-material-design-icons/Delete.vue"
    import ContentCopy from "vue-material-design-icons/ContentCopy.vue"
    import ContentSave from "vue-material-design-icons/ContentSave.vue"
    import FileDocumentEdit from "vue-material-design-icons/FileDocumentEdit.vue"

    import {KsId, KsIconButton, KsPassword} from "@kestra-io/design-system"
    import {KsFilter as KSFilter} from "@kestra-io/design-system"
    import {routeQueryToQueryFilters} from "../../utils/queryFilters"
    import NamespaceSelect from "../namespaces/components/NamespaceSelect.vue"

    import action from "../../models/action"
    import resource from "../../models/resource"
    import * as Utils from "../../utils/utils"
    import {useToast} from "../../utils/toast"
    import {apiUrl} from "override/utils/route"
    import {useClient} from "@kestra-io/kestra-sdk"
    import {storageKeys} from "../../utils/constants"
    import * as SecretsAPI from "@kestra-io/kestra-sdk/secrets"
    import {useAuthStore} from "override/stores/auth"
    import {useNamespacesStore} from "override/stores/namespaces"
    import {useApiStore} from "../../stores/api"
    import {useSecretsFilter} from "../filter/configurations"
    import {useTableColumns} from "../../composables/useTableColumns"
    import {useDiscardGuard} from "../../composables/useDiscardGuard"

    const secretsFilter = useSecretsFilter()

    interface SecretForm {
        value: string;
        namespace?: string;
        key?: string;
        description?: string;
        update?: boolean;
    }

    interface NamespaceSecret {
        key: string;
        namespace?: string;
        description?: string;
    }

    const props = withDefaults(defineProps<{
        addSecretModalVisible?: boolean;
        namespace?: string;
        filterable?: boolean;
        keyOnly?: boolean;
        paneView?: boolean;
        namespaceColumn?: boolean;
        includeInherited?: boolean;
    }>(), {
        addSecretModalVisible: false,
        namespace: undefined,
        filterable: true,
        keyOnly: false,
        paneView: false,
        namespaceColumn: undefined,
        includeInherited: false,
    })

    const emit = defineEmits<{
        "update:addSecretModalVisible": [value: boolean];
        "update:isSecretReadOnly": [value: boolean];
        hasData: [value: boolean];
    }>()

    const {t} = useI18n()
    const toast = useToast()
    const route = useRoute()
    const router = useRouter()
    const authStore = useAuthStore()
    const namespacesStore = useNamespacesStore()
    const apiStore = useApiStore()

    const form = ref<FormInstance>()

    const total = ref(0)
    const hasData = ref<boolean>()
    const areNamespaceSecretsReadOnly = ref(false)
    const secrets = ref<(NamespaceSecret & {namespace?: string})[]>()

    // dsh managed secrets：DB 托管的 (namespace,key) 集合——仅托管行可增删改，
    // 环境变量注入的 secret（SECRET_*）保持只读。键格式 `${namespace}\u0000${key}`。
    const managedKeys = ref(new Set<string>())
    // key → 所属 namespace 列表（字典序）：OSS list 端点只返回扁平 key，
    // 用 managed 端点反查给每行补 namespace 列（DB 托管行），env 行保持无 namespace（只读）。
    const keyNamespaces = ref(new Map<string, string[]>())
    // `${namespace}\u0000${key}` → 元数据（description）：列表协议不返回 description，
    // 由 managed 端点补齐展示。
    const secretMeta = ref(new Map<string, {description?: string}>())
    const axios = useClient()

    async function loadManagedKeys(): Promise<Set<string>> {
        try {
            const response = await axios.get(`${apiUrl()}/secrets/managed`)
            // managed 返回 {"secrets":[{namespace,key,description}]}——一次拿到
            // 托管行判定（ns+key）、namespace 反查、description 展示三份信息。
            const list: {namespace?: string; key?: string; description?: string}[] = response.data?.secrets ?? []
            const keys = new Set<string>()
            const nsByKey = new Map<string, Set<string>>()
            const metaByNsKey = new Map<string, {description?: string}>()
            for (const item of list ?? []) {
                const namespace = item.namespace
                const key = item.key
                if (namespace === undefined || key === undefined) continue
                keys.add(`${namespace}\u0000${key}`)
                if (!nsByKey.has(key)) nsByKey.set(key, new Set())
                nsByKey.get(key)!.add(namespace)
                metaByNsKey.set(`${namespace}\u0000${key}`, {description: item.description})
            }
            managedKeys.value = keys
            const sorted = new Map<string, string[]>()
            for (const [key, namespaces] of nsByKey) {
                sorted.set(key, [...namespaces].sort())
            }
            keyNamespaces.value = sorted
            secretMeta.value = metaByNsKey
            return keys
        } catch {
            // 后端未启用 managed secrets（未配置加密密钥）→ 全部只读
            managedKeys.value = new Set()
            keyNamespaces.value = new Map()
            secretMeta.value = new Map()
            return new Set()
        }
    }

    const secret = ref<SecretForm>({
        namespace: props.namespace,
        key: undefined,
        value: "",
        description: undefined,
        update: undefined,
    })

    const secretBaseline = ref("")
    const {guardedClose: guardSecretClose} = useDiscardGuard(() => JSON.stringify(secret.value) !== secretBaseline.value)
    const beforeSecretClose = (done: () => void) => guardSecretClose(() => done())

    const hasNamespaceColumn = props.namespace === undefined || props.namespaceColumn

    const storageKey = hasNamespaceColumn
        ? storageKeys.DISPLAY_SECRETS_COLUMNS
        : storageKeys.DISPLAY_NAMESPACE_SECRETS_COLUMNS

    const optionalColumns = computed(() => {
        const columns = [
            {
                label: t("namespace"),
                prop: "namespace",
                default: true,
                description: t("filter.table_column.secrets.namespace"),
            },
            {
                label: t("description"),
                prop: "description",
                default: true,
                description: t("filter.table_column.secrets.description"),
            },
        ]

        return columns.filter(col => {
            if (col.prop === "namespace" && !hasNamespaceColumn) return false
            if (col.prop === "description" && props.keyOnly) return false
            return true
        })
    })

    const {visibleColumns: displayColumns, updateVisibleColumns: updateDisplayColumns} = useTableColumns({
        columns: optionalColumns.value,
        storageKey: storageKey,
    })

    const visibleColumns = computed(() =>
        displayColumns.value
            ?.map(prop => optionalColumns.value?.find(c => c.prop === prop))
            ?.filter(Boolean) as any[],
    )

    const secretModalTitle = computed(() => {
        return secret.value?.update
            ? t("secret.update", {name: secret.value?.key})
            : t("secret.add")
    })

    const addSecretDrawerVisible = computed({
        get() {
            return props.addSecretModalVisible
        },
        set(newValue: boolean) {
            emit("update:addSecretModalVisible", newValue)
        },
    })

    const checkSecretValue = (_rule: any, _value: any, callback: any) => {
        // 创建：值必填；更新：空值 = 不修改秘密值（防止把占位空值写回真实值）
        if (!secret.value?.update && (secret.value.value === undefined || secret.value.value.trim().length === 0)) {
            callback(new Error("Value must not be empty."))
        } else {
            callback()
        }
    }

    const rules = {
        key: [
            {required: true, trigger: "change"},
        ],
        value: [
            {
                validator: checkSecretValue,
                trigger: ["blur"],
                required: false,
            },
        ],
        secret: [
            {required: true, trigger: "change"},
        ],
    }

    const canUpdate = (item: NamespaceSecret & {namespace?: string}) => {
        return item?.namespace !== undefined &&
            managedKeys.value.has(`${item.namespace}\u0000${item.key}`) &&
            authStore.user?.isAllowed(resource.SECRET, action.UPDATE, item.namespace) &&
            !areNamespaceSecretsReadOnly.value
    }

    const canDelete = (item: NamespaceSecret & {namespace?: string}) => {
        return item?.namespace !== undefined &&
            managedKeys.value.has(`${item.namespace}\u0000${item.key}`) &&
            authStore.user?.isAllowed(resource.SECRET, action.DELETE, item.namespace) &&
            !areNamespaceSecretsReadOnly.value
    }

    const dataTable = useTemplateRef("dataTable")

    const loadQuery = (base: any) => {
        const {page: _p, size: _s, sort: _so, ...rest} = route.query
        const nonFilterRest = Object.fromEntries(
            Object.entries(rest).filter(([key]) => !key.startsWith("filters[")),
        )
        return _merge(base, nonFilterRest)
    }

    const namespaceFilter = (namespace: string) =>
        [{field: "namespace" as const, operation: "EQUALS" as const, value: namespace}]

    const loadData = async ({page, size, sort}: {page: number; size: number; sort?: string}) => {
        const activeFilters = routeQueryToQueryFilters(route.query)
        const secretsResponse = await SecretsAPI.listSecrets(loadQuery({
            size,
            page,
            sort: sort ?? String(route.query.sort ?? "key:asc"),
            filters: [
                ...activeFilters,
                ...(props.namespace === undefined ? [] : namespaceFilter(props.namespace)),
            ],
        }))

        emit("update:isSecretReadOnly", secretsResponse.readOnly ?? false)

        let allSecrets = secretsResponse.results ?? []

        if (props.includeInherited && props.namespace) {
            const parentNamespaces = Utils.getParentNamespaces(props.namespace).slice(0, -1)

            for (const parentNs of parentNamespaces) {
                const parentSecretsResponse = await SecretsAPI.listSecrets(loadQuery({
                    filters: [...activeFilters, ...namespaceFilter(parentNs)],
                }))

                const parentSecrets = parentSecretsResponse?.results ?? []
                if (parentSecrets.length > 0) {
                    const currentKeys = new Set(allSecrets.map((s: any) => s?.key).filter(Boolean))
                    const newSecrets = parentSecrets.filter(
                        (s: any) => s?.key && !currentKeys.has(s.key),
                    )
                    allSecrets.push(...newSecrets)
                }
            }
        }

        hasData.value = (allSecrets.length ?? 0) !== 0
        // dsh：OSS list 端点恒返回 readOnly=true（环境变量模式）。启用 DB 托管
        // （managed 端点返回数据）后，托管行可增删改，仅 env 行保持只读。
        const managed = await loadManagedKeys()
        areNamespaceSecretsReadOnly.value = (secretsResponse.readOnly ?? false) && managed.size === 0
        // 等 managed 元数据就绪后再标注 namespace/description：DB 托管行显示
        // 所属 namespace 与 description（列表协议只返回扁平 key），env 行（SECRET_*）
        // 无 namespace 保持只读。
        secrets.value = allSecrets.map((s: any) => {
            const ns = keyNamespaces.value.get(s?.key)?.[0]
            if (ns === undefined) return s
            const meta = secretMeta.value.get(`${ns}\u0000${s.key}`)
            return {...s, namespace: ns, description: s.description ?? meta?.description}
        })
        total.value = secretsResponse.total ?? 0
        loadedFilterKey.value = filterQueryKey.value
    }

    const urlPage = computed(() => Number(route.query.page) || 1)
    const urlSize = computed(() => Number(route.query.size) || 25)

    const filterQueryKey = computed(() => {
        const {page: _p, size: _s, sort: _so, ...filters} = route.query
        return JSON.stringify(filters)
    })

    // filter/搜索变化（KSFilter 内部更新 URL）后必须重新加载列表：
    // KsDataTable 只监听 page/size/sort，不监听 route.query —— 缺失此 watch 时
    // 「清除所有」与搜索框 x 清除都只改 URL，列表不刷新（用户实测 403 页同款问题）。
    watch(filterQueryKey, () => {
        dataTable.value?.resetAndReload()
    })

    const hasActiveFilters = computed(() => routeQueryToQueryFilters(route.query).length > 0)

    // The filter query the rows on screen were loaded for; until it catches up, `total` still answers
    // for the previous one.
    const loadedFilterKey = ref<string>()

    // Judged on the total rather than the loaded page: a page past the end of a shrunken list is
    // empty without the list being empty.
    const showEmptyState = computed(() =>
        loadedFilterKey.value === filterQueryKey.value &&
        total.value === 0 &&
        !hasActiveFilters.value,
    )

    watch(filterQueryKey, () => {
        dataTable.value?.resetAndReload()
    })

    const updateSecretModal = (secretData: NamespaceSecret) => {
        secret.value.namespace = secretData?.namespace
        secret.value.key = secretData?.key
        secret.value.description = secretData?.description
        secret.value.value = ""
        secret.value.update = true
        addSecretDrawerVisible.value = true
    }

    const copyKey = async (key: string) => {
        await Utils.copy(`{{ secret('${key}') }}`)
        toast.success(t("copied"))
    }

    const removeSecret = ({key, namespace}: {key: string; namespace: string}) => {
        toast.confirm(t("delete confirm", {name: key}), () => {
            return namespacesStore
                .deleteSecrets({namespace, key})
                .then(() => {
                    toast.deleted(key)
                })
                .then(() => dataTable.value?.reload())
        })
    }

    // 是否随本次保存提交新秘密值：更新模式值为空 = 不修改值（仅元数据）；
    // 创建模式恒传值。更新永远是 PATCH（value 可选），创建永远是 POST。
    const shouldUpdateValue = () => {
        if (!secret.value?.update) return true
        const v = secret.value?.value
        return v !== undefined && v !== null && v.trim() !== ""
    }

    const saveSecret = (formRef: FormInstance | undefined) => {
        if (!formRef) return

        formRef.validate((valid: boolean) => {
            if (!valid) {
                return
            }

            const secretData: any = {
                key: secret.value?.key,
                description: secret.value?.description,
            }

            const updateValue = shouldUpdateValue()
            if (updateValue) {
                secretData.value = secret.value?.value
            }

            const actionMethod = secret.value?.update === true
                ? namespacesStore.patchSecret
                : namespacesStore.createSecrets

            // Snapshot before the request: resetForm() swaps secret.value out when the drawer closes,
            // and the .then() would then read the flag off a different object.
            const wasUpdate = secret.value?.update === true
            const namespace = secret.value?.namespace

            actionMethod({namespace: namespace as string, secret: secretData})
                .then(() => {
                    apiStore.posthogEvents({
                        type: wasUpdate ? "SECRET_UPDATED" : "SECRET_CREATED",
                        secret_type: "secret",
                        namespace,
                    })

                    secret.value!.update = true
                    toast.saved(secret.value?.key || "")
                    addSecretDrawerVisible.value = false
                    resetForm()
                    dataTable.value?.reload()
                })
        })
    }

    const resetForm = () => {
        secret.value = {
            namespace: props.namespace,
            key: undefined,
            value: "",
            description: undefined,
            update: undefined,
        }
    }

    watch(() => props.addSecretModalVisible, (newValue) => {
        if (newValue) {
            nextTick(() => {
                secretBaseline.value = JSON.stringify(secret.value)
            })
        } else {
            resetForm()
        }
    })

    watch(hasData, (newValue, oldValue) => {
        if (oldValue !== newValue) {
            emit("hasData", newValue!)
        }
    })
</script>
<style scoped lang="scss">
    .secrets-table {
        display: flex;
        flex-direction: column;
        min-height: 0;
    }

    .secret-tag-row {
        display: flex;
        align-items: center;
        gap: var(--ks-spacing-3);
        margin-bottom: var(--ks-spacing-2);

        .tag-key {
            flex: 2;
        }

        .tag-value {
            flex: 3;
        }
    }

    .no-pointer-events {
        pointer-events: none;
    }

    .field-item :deep(.kel-form-item__content) {
        flex: 0 0 260px;
        max-width: 260px;
    }

    .field-item :deep(.kel-form-item__content) > * {
        width: 100%;
    }
</style>
