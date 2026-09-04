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
                <Environment />
                <UserItem />
            </div>
        </template>
    </SideBar>
</template>

<script setup lang="ts">
    import {useBreakpoints, breakpointsElement} from "@vueuse/core"

    import SideBar from "../../components/layout/SideBar.vue"
    import ProductTourItem from "../../components/onboarding/tour/ProductTourItem.vue"
    import UserItem from "override/components/auth/UserItem.vue"
    import Environment from "../../components/layout/Environment.vue"


    import {useLeftMenu} from "override/components/useLeftMenu"

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
