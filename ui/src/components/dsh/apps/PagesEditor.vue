<template>
    <!--
        dsh fork: /ui/main/pages —— 可视化页面设计器（SPA 内嵌，iframe 隔离）。
        套用 kestra-ui 外壳（最左侧菜单），内容区 = iframe 内的 amis-editor 完整编辑器
        （编辑器自身的页面树成为"中部菜单"）。
        隔离原因：amis-editor 的可视化编辑可带入任意 CSS（用户 schema 渲染、amis 主题
        css 的全局选择器），必须与 kestra-ui 外壳物理隔离 —— iframe 提供独立 document，
        编辑器注入的任何样式只作用于 iframe 内部，永不污染外壳及其他页面。
        主题经 iframe query + postMessage 同步（kestra-ui 亮/暗切换时跟随）。
    -->
    <iframe
        ref="frame"
        class="dsh-pages-frame"
        title="可视化页面设计器"
        :src="frameSrc"
        @load="onFrameLoad"
    />
</template>

<script setup lang="ts">
    import {computed, onBeforeUnmount, ref, watch} from "vue"
    import {storeToRefs} from "pinia"
    import {useMiscStore} from "../../../override/stores/misc"

    const miscStore = useMiscStore()
    const {theme} = storeToRefs(miscStore)

    const frame = ref<HTMLIFrameElement | null>(null)

    function themeToEditorTheme(t: string): "dark" | "light" {
        if (t === "dark" || t === "dark-2") {
            return "dark"
        }
        if (t === "light") {
            return "light"
        }
        // syncWithSystem（及未知值）：跟随系统
        return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
    }

    const editorTheme = computed(() => themeToEditorTheme(theme.value))

    // 初始主题通过 query 传给 iframe（iframe 首屏即正确主题，避免闪亮/闪暗）
    const frameSrc = computed(() => `/apps/designer?embedded=1&theme=${editorTheme.value}`)

    function notifyTheme() {
        const f = frame.value
        if (f?.contentWindow) {
            f.contentWindow.postMessage(
                {type: "dsh-editor-theme", theme: editorTheme.value},
                window.location.origin,
            )
        }
    }

    // 主题切换（用户菜单 / Ctrl+Shift+L / 系统跟随）→ 同步给 iframe
    watch(editorTheme, () => notifyTheme())

    function onFrameLoad() {
        notifyTheme()
    }

    onBeforeUnmount(() => {
        // 不做任何清理：iframe 随组件卸载而销毁，其 document 及注入样式自动消失
    })
</script>

<style scoped>
    .dsh-pages-frame {
        width: 100%;
        height: 100%;
        border: 0;
        display: block;
        background: transparent;
    }
</style>
