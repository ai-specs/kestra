<template>
    <SideBar
        v-if="menu"
        :menu
        :showLink
        :collapsed
        @menu-collapse="onCollapse"
        :class="{overlay: verticalLayout}"
    >
        <template #footer>
            <div class="left-menu-footer">
                <ProductTourItem v-if="!collapsed" />
                <AdminItem :tabs="visibleAdminTabs" />
                <Environment />
                <Auth />
            </div>
        </template>
    </SideBar>
</template>

<script setup lang="ts">
    import {computed} from "vue"
    import {useBreakpoints, breakpointsElement} from "@vueuse/core"

    import SideBar from "../../components/layout/SideBar.vue"
    import AdminItem from "../../components/admin/AdminItem.vue"
    import ProductTourItem from "../../components/onboarding/tour/ProductTourItem.vue"
    import Auth from "override/components/auth/Auth.vue"
    import Environment from "../../components/layout/Environment.vue"


    import {useLeftMenu} from "override/components/useLeftMenu"
    import {useAdminTabs} from "../../composables/useAdminTabs"

    withDefaults(defineProps<{
        showLink?: boolean
        collapsed?: boolean
    }>(), {
        showLink: true,
        collapsed: false,
    })

    const emit = defineEmits<{
        (e: "menu-collapse", folded: boolean): void
    }>()

    const verticalLayout = useBreakpoints(breakpointsElement).smallerOrEqual("sm")
    const {menu} = useLeftMenu()
    const {adminTabs} = useAdminTabs()

    // dsh fork: 过滤 admin 折叠菜单中企业版（EE）锁定标签（locked 标记跟随上游），
    // 与 useLeftMenu 的 hideLockedMenu 保持一致——EE 功能在 dsh 部署中不使用。
    const visibleAdminTabs = computed(() => adminTabs.value.filter((tab) => !(tab as {locked?: boolean}).locked))

    function onCollapse(folded: boolean) {
        emit("menu-collapse", folded)
    }
</script>

<style scoped lang="scss">
    #side-menu {
        .kel-select {
            transition: all 0.2s ease;
            background-color: transparent;
        }
    }

    .left-menu-footer {
        display: flex;
        flex-direction: column;
        gap: var(--ks-spacing-2);
        padding: var(--ks-spacing-4);
    }
</style>
