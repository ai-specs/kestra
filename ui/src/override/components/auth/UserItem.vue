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
                    <div v-if="profile && profile.email" class="user-item__line">{{ profile.email }}</div>
                    <div v-if="roleLabel" class="user-item__line">
                        {{ $t("dsh.account.roles") }}: {{ roleLabel }}
                    </div>
                </div>
            </div>
        </template>

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
</style>

<!-- eslint-disable-next-line vue/enforce-style-attribute -->
<style lang="scss">
    // Reuses the .user-select dropdown chrome shipped by Auth.vue (width, shadow, menu-item).
</style>
