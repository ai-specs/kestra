// @ts-check
// Standalone dsh Apps shell build — a separate, minimal vite build for apps.html.
// It deliberately does NOT use the SPA plugin set (federation, consolidateChunks, PWA,
// stripDeadPrebuildDefault, loaderFragment): the module-federation bootstrap would hijack
// the apps entry and boot the whole Kestra SPA instead of the standalone amis app
// (verified: apps.html loaded mf-entry-bootstrap-* and rendered the SPA 404 page).
// Output lands in the same outDir as the main build but does NOT empty it (the main
// `vite build --emptyOutDir` runs first); base "" keeps relative asset references that
// UiIndexService rewrites to /ui/assets/... at serve time.

import path from "path"
import {defineConfig} from "vite"

export default defineConfig(() => ({
    base: "",
    build: {
        outDir: "../webserver/src/main/resources/ui",
        emptyOutDir: false,
        // amis-editor-core/style.css carries legacy IE media-query hacks
        // (@media (min-width: 0\0)) that the default LightningCSS minifier rejects;
        // esbuild's CSS minifier passes them through, so use it instead.
        cssMinify: "esbuild",
        rollupOptions: {
            input: {
                apps: path.resolve(__dirname, "apps.html"),
                // 第二入口：amis-editor 独立构建（/apps/designer 与 /apps/{app}/{page}/edit 共用，
                // 前端按 URL 分流），与渲染入口分离 —— 渲染页不携带编辑器体积。
                "apps-editor": path.resolve(__dirname, "apps-editor.html"),
            },
            output: {
                // 固定前缀在多入口下两个入口 chunk 同名（仅 hash 不同），[name] 才能区分：
                // apps-apps-*.js（渲染入口）/ apps-apps-editor-*.js（编辑器入口）
                entryFileNames: "assets/apps-[name]-[hash].js",
                chunkFileNames: "assets/apps-[hash].js",
                assetFileNames: "assets/apps-[hash][extname]",
            },
        },
    },
    resolve: {
        alias: [
            {find: "override", replacement: path.resolve(__dirname, "src/override/")},
        ],
    },
    // The root tsconfig is Vue-oriented (jsx: preserve + jsxImportSource: vue) — the
    // rolldown parser would reject the React JSX in src/apps/editor.tsx. The apps build
    // is React-only, so override the JSX transform here.
    esbuild: {
        jsx: "automatic",
        jsxImportSource: "react",
    },
    css: {
        lightningcss: {
            // amis-editor-core/style.css carries legacy IE media-query hacks
            // (@media (min-width: 0\0)) that LightningCSS minify rejects — strip them.
            errorRecovery: true,
        },
    },
    optimizeDeps: {
        include: ["amis", "amis-core", "react", "react-dom", "react-dom/client", "amis-editor", "i18n-runtime", "amis-theme-editor-helper"],
    },
}))
