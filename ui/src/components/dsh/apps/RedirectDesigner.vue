<script setup lang="ts">
    // dsh fork: SPA 跳板 —— 菜单 href 是 Vue 路由路径，不能直接挂外部 URL；
    // /apps/designer 是独立 HTML 入口（UiAppController + apps-editor.html），
    // 用整页跳转离开 SPA（docs/dsh-apps-amis-editor.md §4.3/§6.5）。
    window.location.replace("/apps/designer");
</script>

<template>
    <div />
</template>
