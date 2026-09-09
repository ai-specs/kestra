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
        rollupOptions: {
            input: {
                apps: path.resolve(__dirname, "apps.html"),
            },
            output: {
                // keep apps chunks clearly separated from the SPA assets
                entryFileNames: "assets/apps-entry-[hash].js",
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
    optimizeDeps: {
        include: ["amis", "amis-core", "react", "react-dom", "react-dom/client"],
    },
}))
