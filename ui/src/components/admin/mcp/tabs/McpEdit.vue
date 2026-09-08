<template>
    <div class="mcp-edit">
        <KsForm
            ref="formRef"
            :model="form"
            labelPosition="top"
            @submit.prevent="save"
        >
            <KsFormItem
                :label="$t('mcp.server_id')"
                prop="id"
                required
                labelPosition="left"
                class="id-row"
                :rules="idRules"
            >
                <KsInput
                    v-model="form.id"
                    :placeholder="$t('mcp.id_placeholder')"
                    :disabled="idDisabled"
                    class="mono id-input"
                >
                    <template
                        v-if="idDisabled"
                        #suffix
                    >
                        <Lock :size="16" />
                    </template>
                </KsInput>
            </KsFormItem>

            <KsFormItem :label="$t('description')">
                <KsInput
                    v-model="form.description"
                    type="textarea"
                    :rows="2"
                    :placeholder="$t('description')"
                    :disabled="readOnly"
                />
            </KsFormItem>

            <KsFormItem :label="$t('mcp.instructions')">
                <KsInput
                    v-model="form.instructions"
                    type="textarea"
                    :rows="3"
                    :placeholder="$t('mcp.instructions')"
                    class="mono"
                    :disabled="readOnly"
                />
            </KsFormItem>

            <!-- dsh：本项目语境下不需要 PUBLIC（公开 MCP server），serverType 恒为 PRIVATE，
                 认证方式由下方单选决定；上游的私有/公开可见性开关已移除 -->
            <KsFormItem v-if="isPrivate">
                <KsRadioCardGroup
                    v-model="form.authType"
                    :options="authOptions"
                    :ariaLabel="$t('mcp.auth_type')"
                />
            </KsFormItem>

            <KsFormItem
                v-if="isOAuth"
                :label="$t('mcp.oauth_provider')"
                prop="oauthProvider"
                :rules="oauthProviderRules"
            >
                <!-- dsh：仅一个 OAuth 提供商时不提供下拉框，直接显示固定值（不可更改） -->
                <KsSelect
                    v-if="oauthProviders.length > 1"
                    v-model="form.oauthProvider"
                    :placeholder="$t('mcp.oauth_provider_placeholder')"
                    :disabled="readOnly"
                    class="full-width"
                >
                    <KsOption
                        v-for="provider in oauthProviders"
                        :key="provider"
                        :label="provider"
                        :value="provider"
                    />
                </KsSelect>
                <div
                    v-else
                    class="fixed-value"
                >
                    {{ form.oauthProvider || (oauthProviders[0] ?? "") }}
                </div>
            </KsFormItem>

            <KsFormItem
                v-if="isOAuth"
                :label="$t('mcp.scopes_supported')"
            >
                <KsSelect
                    v-model="form.oauthScopesSupported"
                    multiple
                    filterable
                    allowCreate
                    defaultFirstOption
                    :placeholder="$t('mcp.scopes_supported_placeholder')"
                    :disabled="readOnly"
                    class="full-width"
                />
                <div class="field-hint">
                    {{ $t("mcp.scopes_supported_hint") }}
                </div>
            </KsFormItem>

            <KsFormItem
                :label="$t('enabled')"
                labelPosition="left"
                class="spread-row"
            >
                <KsSwitch
                    v-model="enabled"
                    :disabled="readOnly"
                />
            </KsFormItem>

            <!-- dsh：内容未变更时不显示操作按钮，避免无意义保存 -->
            <div
                v-if="canSave && isDirty"
                class="form-actions"
            >
                <KsButton @click="cancel">
                    {{ $t("cancel") }}
                </KsButton>
                <KsButton
                    type="primary"
                    :disabled="submitting"
                    @click="save"
                >
                    {{ isUpdate ? $t("save") : $t("create") }}
                </KsButton>
            </div>
        </KsForm>
    </div>
</template>

<script lang="ts" setup>
    import {computed, onMounted, ref, watch} from "vue"
    import {useI18n} from "vue-i18n"
    import {useRoute, useRouter} from "vue-router"

    import {useMcpStore, type McpServerPayload} from "../../../../stores/mcp"
    import {useHelpers} from "../useHelpers"
    import {useMiscStore} from "override/stores/misc"
    import {useAuthStore} from "override/stores/auth"

    import {useToast} from "../../../../utils/toast"

    import Lock from "vue-material-design-icons/Lock.vue"
    import LockOutline from "vue-material-design-icons/LockOutline.vue"

    import resource from "../../../../models/resource"
    import action from "../../../../models/action"
    import type {FormInstance} from "@kestra-io/design-system"

    const {t} = useI18n({useScope: "global"})
    const route = useRoute()
    const router = useRouter()
    const toast = useToast()
    const mcpStore = useMcpStore()
    const authStore = useAuthStore()
    const miscStore = useMiscStore()
    const {listRoute} = useHelpers()

    // dsh：默认 scope 与内置 kestra-oidc 的 scopes_supported 保持一致（含 mcp，供 MCP 过滤器判定）
    const DEFAULT_OAUTH_SCOPES = ["openid", "profile", "email", "mcp"]

    // dsh 定制：BASIC 认证已从本项目移除（OIDC-only 设计，见 docs/deprecated.md §4），
    // OAUTH 走内置 kestra-oidc（OSS 自实现，见 docs/mcp-oauth.md）；API_TOKEN 仅 EE 可用。
    const AUTH_OPTIONS = [
        {value: "OAUTH", labelKey: "mcp.oauth", hintKey: "mcp.oauth_hint", ee: false},
        {value: "API_TOKEN", labelKey: "mcp.api_token", hintKey: "mcp.bearer_token", ee: true},
    ] as const

    type AuthOption = (typeof AUTH_OPTIONS)[number]

    type McpForm = Required<McpServerPayload>

    const defaultForm = (): McpForm => ({
        id: "",
        description: "",
        instructions: "",
        serverType: "PRIVATE",
        authType: "OAUTH", // dsh：默认启用 OAuth
        oauthProvider: "",
        oauthScopesSupported: [...DEFAULT_OAUTH_SCOPES],
        disabled: false,
    })

    const formRef = ref<FormInstance>()
    const form = ref<McpForm>(defaultForm())
    const submitting = ref(false)

    // dsh：pristine 快照对比实现"内容变更后才出现取消/保存"。
    // 服务器加载、系统默认值回填、保存成功后都会重置快照，用户改动才会置脏。
    const pristine = ref<string>(JSON.stringify(form.value))
    const isDirty = computed(() => JSON.stringify(form.value) !== pristine.value)

    const isOss = computed(() => miscStore.configs?.edition === "OSS")
    const oauthProviders = computed<string[]>(() => authStore.auths?.oauths ?? [])
    const noOauthProviders = computed(() => oauthProviders.value.length === 0)

    const isUpdate = computed(() => !!route.params.id)
    // dsh：serverType 恒为 PRIVATE（本项目不需要 PUBLIC），authType 表单始终显示
    const isPrivate = true
    const isOAuth = computed(() => form.value.authType === "OAUTH")

    const canSave = computed(() => {
        if (isUpdate.value) {
            return authStore.user?.isAllowedGlobal?.(resource.MCP_SERVER, action.UPDATE) ?? true
        }
        return authStore.user?.isAllowedGlobal?.(resource.MCP_SERVER, action.CREATE) ?? true
    })
    const readOnly = computed(() => !canSave.value)
    const idDisabled = computed(() => isUpdate.value || readOnly.value)

    const idRules = computed(() => [
        {required: true, message: t("is required", {field: t("id")}), trigger: "blur"},
        {pattern: /^[a-z0-9][a-z0-9_-]*$/, message: t("mcp.id_invalid"), trigger: "blur"},
    ])

    const oauthProviderRules = computed(() => [
        {required: true, message: t("mcp.oauth_provider_required"), trigger: "change"},
    ])

    const enabled = computed({
        get: () => !form.value.disabled,
        set: (value: boolean) => {
            form.value.disabled = !value
        },
    })

    const isOptionDisabled = (opt: AuthOption): boolean => {
        if (readOnly.value) {
            return true
        }
        if (opt.ee && isOss.value) {
            return true
        }
        if (opt.value === "OAUTH" && noOauthProviders.value) {
            return true
        }
        return false
    }

    const authHint = (opt: AuthOption): string => {
        if (opt.value === "OAUTH" && noOauthProviders.value) {
            return t("mcp.no_oauth_providers")
        }
        return t(opt.hintKey)
    }

    const authOptions = computed(() =>
        AUTH_OPTIONS.map((opt) => ({
            value: opt.value,
            label: t(opt.labelKey),
            hint: authHint(opt),
            disabled: isOptionDisabled(opt),
            icon: opt.ee && isOss.value ? LockOutline : undefined,
        })),
    )

    const buildPayload = (): McpServerPayload => {
        const isOauth = form.value.authType === "OAUTH"

        let oauthProvider: string | undefined
        let oauthScopesSupported: string[] | undefined
        if (isOauth) {
            oauthProvider = form.value.oauthProvider || undefined
            oauthScopesSupported = form.value.oauthScopesSupported.length > 0
                ? form.value.oauthScopesSupported
                : undefined
        }

        return {
            id: form.value.id,
            description: form.value.description || undefined,
            instructions: form.value.instructions || undefined,
            serverType: form.value.serverType,
            authType: form.value.authType,
            oauthProvider,
            oauthScopesSupported,
            disabled: form.value.disabled,
        }
    }

    const save = async (): Promise<void> => {
        if (readOnly.value || !formRef.value || submitting.value) {
            return
        }

        await formRef.value.validate(async (valid) => {
            if (!valid) {
                return
            }

            submitting.value = true
            try {
                if (isUpdate.value) {
                    await mcpStore.update(form.value.id, buildPayload())
                    toast.saved(form.value.id)
                } else {
                    const created = await mcpStore.create(buildPayload())
                    toast.saved(created.id)
                    router.push({
                        name: "admin/mcp-servers/update",
                        params: {id: created.id, tab: "edit", tenant: route.params.tenant},
                    })
                }
                // 保存成功后内容与服务器一致，隐藏操作按钮
                pristine.value = JSON.stringify(form.value)
            } catch (e) {
                console.error("Failed to save MCP server", e)
            } finally {
                submitting.value = false
            }
        }).catch(() => {})
    }

    const cancel = (): void => {
        router.push(listRoute.value)
    }

    onMounted(async () => {
        if (!authStore.auths) {
            await authStore.loadAuths({})
        }
        // dsh：唯一 OAuth 提供商时自动作为默认值（可能在 server watch 之后才可用）
        if (oauthProviders.value.length === 1 && !form.value.oauthProvider) {
            form.value.oauthProvider = oauthProviders.value[0]
            // 系统默认回填不算用户变更，重置脏标记
            pristine.value = JSON.stringify(form.value)
        }
    })

    watch(
        () => mcpStore.server,
        (server) => {
            if (server) {
                form.value = {
                    id: server.id,
                    description: server.description ?? "",
                    instructions: server.instructions ?? "",
                    // dsh：PUBLIC 已移除，存量 PUBLIC 服务器打开时强制映射为 PRIVATE
                    serverType: "PRIVATE",
                    // dsh：BASIC 选项已移除，存量 BASIC 服务器打开时映射为 OAuth（默认启用）
                    authType: server.authType === "OAUTH" || server.authType === "API_TOKEN"
                        ? server.authType
                        : "OAUTH",
                    // dsh：唯一提供商自动作为默认值；服务器未存 scope 时回退默认集
                    oauthProvider: server.oauthProvider ?? (oauthProviders.value.length === 1 ? oauthProviders.value[0] : ""),
                    oauthScopesSupported: server.oauthScopesSupported?.length
                        ? server.oauthScopesSupported
                        : [...DEFAULT_OAUTH_SCOPES],
                    disabled: server.disabled,
                }
                pristine.value = JSON.stringify(form.value)
            } else if (!isUpdate.value) {
                form.value = defaultForm()
                pristine.value = JSON.stringify(form.value)
            }
        },
        {immediate: true},
    )
</script>

<style lang="scss" scoped>
    .mcp-edit {
        max-width: 653px;
        border: 1px solid var(--ks-border-default);
        border-radius: 8px;
        box-shadow: 0px 2px 8px 0px var(--ks-shadow-surface);
        background: var(--ks-bg-surface);
        padding: var(--ks-spacing-4);
        margin-block-start: var(--ks-spacing-7);
        margin-inline: auto;
    }

    .mono :deep(input),
    .mono :deep(textarea) {
        font-family: var(--ks-font-family-mono);
    }

    .mcp-edit :deep(textarea) {
        resize: none;
    }

    .mcp-edit :deep(textarea)::-webkit-scrollbar {
        width: 0.5rem;
    }

    .mcp-edit :deep(textarea)::-webkit-scrollbar-thumb {
        background-color: var(--ks-scrollbar-content);
        background-clip: padding-box;
        border: 2px solid transparent;
        border-radius: 999px;
    }

    :deep(.kel-form-item__label) {
        font-weight: var(--ks-font-weight-semibold);
    }

    .mcp-edit :deep(.kel-form-item:not(:first-child)) {
        border-top: 1px solid var(--ks-border-subtle);
        padding-top: var(--ks-spacing-4);
    }

    .id-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    .id-row :deep(.kel-form-item__content) {
        flex: 0 0 auto;
    }

    .spread-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    .spread-row :deep(.kel-form-item__content) {
        flex: 0 0 auto;
    }

    .spread-row:last-child {
        margin-bottom: 0;
    }

    .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--ks-spacing-2);
        border-top: 1px solid var(--ks-border-subtle);
        padding-top: var(--ks-spacing-4);
    }

    .id-input {
        width: 170px;
        min-height: 30px;
    }

    .field-hint {
        margin-top: var(--ks-spacing-1);
        font-size: var(--ks-font-size-sm);
        color: var(--ks-text-secondary);
    }

    /* dsh：唯一 OAuth 提供商时的只读展示（不可编辑） */
    .fixed-value {
        display: flex;
        align-items: center;
        min-height: 32px;
        padding: 0 var(--ks-spacing-3);
        border: 1px solid var(--ks-border-default);
        border-radius: var(--ks-radius-sm, 6px);
        background: var(--ks-bg-subtle, rgba(0, 0, 0, 0.03));
        font-size: var(--ks-font-size-sm);
        color: var(--ks-text-secondary);
        cursor: not-allowed;
        user-select: none;
    }

    .type-hint {
        margin-bottom: var(--ks-spacing-4);
    }

    .full-width {
        width: 100%;
    }
</style>