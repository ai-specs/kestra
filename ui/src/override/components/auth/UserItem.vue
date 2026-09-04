<template>
    <KsSelect
        class="user-item"
        popperClass="user-select border border-0"
        placement="right-end"
        :popperOffset="20"
        :showArrow="false"
        :suffixIcon="ChevronRight"
        :placeholder="username || $t('dsh.account.notLoggedIn')"
    >
        <template #prefix>
            <span class="user-item__avatar">
                <AccountOutline :size="18" />
            </span>
        </template>

        <template #label>
            {{ username || $t("dsh.account.notLoggedIn") }}
        </template>

        <template #header>
            <div class="menu-item user-item__header">
                <AccountOutline class="menu-icon" />
                <div class="user-item__info">
                    <div class="user-item__title">{{ $t("dsh.account.title") }}</div>
                    <div class="user-item__line">{{ username }}</div>
                    <div v-if="profile && profile.name && profile.name !== username" class="user-item__line">
                        {{ profile.name }}
                    </div>
                    <div v-if="profile && profile.email && profile.email !== username" class="user-item__line">{{ profile.email }}</div>
                    <div v-if="roleLabel" class="user-item__line">
                        {{ $t("dsh.account.roles") }}: {{ roleLabel }}
                    </div>
                </div>
            </div>
        </template>

        <KsOption label="tour" value="tour">
            <RouterLink :to="startTutorial" class="menu-item">
                <RocketLaunchOutline class="menu-icon" />
                {{ $t("product_tour") }}
            </RouterLink>
        </KsOption>
        <KsOption label="preferences" value="preferences">
            <a class="menu-item" href="#" @click.prevent="openPreferences">
                <CogOutline class="menu-icon" />
                {{ $t("dsh.account.preferences") }}
            </a>
        </KsOption>

        <template #footer>
            <KsOption value="logout" class="list-unstyled" @click="logout">
                <div class="menu-item">
                    <Logout class="menu-icon" />
                    {{ $t("dsh.account.logout") }}
                </div>
            </KsOption>
        </template>
    </KsSelect>
</template>

<script setup lang="ts">
    import {computed, onMounted, ref} from "vue"
    import {useRoute, useRouter} from "vue-router"

    import AccountOutline from "vue-material-design-icons/AccountOutline.vue"
    import ChevronRight from "vue-material-design-icons/ChevronRight.vue"
    import CogOutline from "vue-material-design-icons/CogOutline.vue"
    import Logout from "vue-material-design-icons/Logout.vue"
    import RocketLaunchOutline from "vue-material-design-icons/RocketLaunchOutline.vue"

    interface MeProfile {
        username: string
        name: string
        email: string
        roles: string[]
        admin: boolean
    }

    const route = useRoute()
    const router = useRouter()

    const profile = ref<MeProfile | undefined>(undefined)

    const username = computed(() => profile.value?.username)
    const roleLabel = computed(() =>
        profile.value?.roles?.length ? profile.value.roles.join(", ") : ""
    )

    onMounted(async () => {
        // Same-origin cookie auth; GET requests bypass Kestra's CsrfTokenFilter.
        const API_BASE = `${import.meta.env.VITE_APP_API_URL || ""}${window.KESTRA_BASE_PATH || ""}`.replace(/\/$/, "") || window.location.origin
        try {
            const res = await fetch(`${API_BASE}/api/v1/oidc/users/me`, {
                credentials: "include",
            })
            if (res.ok) profile.value = await res.json()
        } catch {
            // 未登录/网络失败：按钮退化为未登录占位，弹出层仍提供注销入口
        }
    })

    const startTutorial = computed(() => ({
        name: "ai",
        query: {tour: "start"},
        params: {tenant: route.params.tenant},
    }))

    function openPreferences() {
        router.push({name: "preferences", params: {tenant: route.params.tenant}})
    }

    const logout = () => {
        // dsh: navigate to the IdP logout — it clears oidc_session + JWT + the UI flag cookie
        // server-side and redirects back to /oidc/login (see Auth.vue for the rationale).
        window.location.href = "/oidc/logout"
    }
</script>

<style scoped lang="scss">
    .user-item__avatar {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        margin: 0.25rem;
        border-radius: var(--ks-radius-base);
        background: var(--ks-bg-secondary, rgba(127, 127, 127, 0.18));
    }

    .user-item__header {
        align-items: flex-start;
    }

    .user-item__info {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 0;

        .user-item__title {
            font-size: var(--ks-font-size-2xs);
            font-weight: var(--ks-font-weight-regular);
            color: var(--ks-text-inactive);
        }

        .user-item__line {
            font-size: var(--ks-font-size-sm);
            color: var(--ks-text-primary);
            word-break: break-all;
        }
    }

    :deep(.kel-select__wrapper) {
        padding: 8px 10px !important;
        height: 30px;
        font-size: var(--ks-font-size-xs);
        background-color: transparent;

        &.is-hovering:not(.is-focused) {
            box-shadow: 0 0 0 1px var(--ks-border-subtle) inset;
        }
    }

    .menu-item {
        display: flex;
        align-items: center;
        gap: 1rem;
        color: var(--ks-text-primary);
        text-decoration: none;

        .menu-icon {
            color: var(--ks-text-dim);
            font-size: var(--ks-font-size-xl);
            flex-shrink: 0;
        }
    }
</style>

<!-- eslint-disable-next-line vue/enforce-style-attribute -->
<style lang="scss">
    // 弹层外观：接管自 Auth.vue 的 .user-select 全局块（Auth 已不在挂载树里，
    // 其样式不再进包；popperClass="user-select" 依赖这段才有多宽/描边/投影）。
    .user-select {
        &.kel-select-dropdown {
            width: 328px;
            background: var(--ks-bg-input);
            box-shadow: 2px 3px 3px var(--ks-shadow-element);
            border-radius: var(--kel-border-radius-base);
            border: 1px solid var(--ks-border-default) !important;

            .kel-select-dropdown__item {
                min-height: 30px;
                height: fit-content;
                padding: 10px 16px 8px 16px;
                font-weight: var(--ks-font-weight-bold);
            }

            .kel-select-dropdown__footer {
                padding: 5px 0;

                .kel-select-dropdown__item {
                    margin: 0 !important;
                }
            }
        }
    }

    .user-avatar {
        padding: 0.25rem;
        border-radius: var(--ks-radius-base);
    }
</style>
