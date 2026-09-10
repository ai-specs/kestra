<template>
    <!--
        dsh fork: /ui/main/pages —— 可视化页面设计器（SPA 内嵌）。
        套用 kestra-ui 外壳（最左侧菜单），内容区 = amis-editor 完整编辑器
        （编辑器自身的页面树在 SPA 内成为"中部菜单"）。
        React 编辑器按需动态加载（独立 chunk，不进入 SPA 首包）。
    -->
    <div ref="host" class="dsh-pages-host" data-editor-embedded="true"></div>
</template>

<script setup lang="ts">
    import {onBeforeUnmount, onMounted, ref} from "vue"

    const host = ref<HTMLElement | null>(null)
    let unmountEditor: (() => void) | null = null

    onMounted(async () => {
        const {mountEditor} = await import("../../../apps/editor")
        if (host.value) {
            unmountEditor = mountEditor(host.value, {mode: "designer", embedded: true})
        }
    })

    onBeforeUnmount(() => {
        unmountEditor?.()
        unmountEditor = null
    })
</script>

<style scoped>
    .dsh-pages-host {
        height: 100%;
        min-height: 0;
        display: flex;
    }
</style>
