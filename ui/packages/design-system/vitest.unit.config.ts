import {defineConfig} from "vitest/config"
import vue from "@vitejs/plugin-vue"

/** Node 26's own `localStorage` shadows jsdom's and stays undefined, so let jsdom provide storage. */
process.env.NODE_OPTIONS = `${process.env.NODE_OPTIONS ?? ""} --no-experimental-webstorage`.trim()

export default defineConfig({
    plugins: [vue()],
    test: {
        environment: "jsdom",
        globals: true,
        include: ["tests/**/*.test.ts"],
        setupFiles: ["./tests/units/setup.ts"],
        server: {
            deps: {
                // Same as the root vitest.config.unit.js: element-plus imports
                // `placements` from "@popperjs/core"; externalised in jsdom that resolves
                // popper's CJS build without the named export. Inlining routes both through
                // Vite's transform, which provides it.
                inline: [/element-plus/, "@popperjs/core"],
            },
        },
    },
})
