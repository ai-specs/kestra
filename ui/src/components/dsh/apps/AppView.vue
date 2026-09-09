<template>
    <div class="dsh-app-view">
        <div v-if="loading" class="dsh-app-view__status">Loading app…</div>
        <div v-else-if="error" class="dsh-app-view__status dsh-app-view__error">{{ error }}</div>
        <div ref="container" class="dsh-app-view__container" />
    </div>
</template>

<script setup lang="ts">
    import {nextTick, onBeforeUnmount, onMounted, ref, watch} from "vue";
    import {render as amisRender, type RenderOptions} from "amis";
    import {createRoot, type Root} from "react-dom/client";
    import type {Api, Payload} from "amis-core";
    // amis CSS contains an IE media-query hack that breaks the vite lightningcss minifier,
    // so inject it at runtime as a raw string instead of going through the css pipeline.
    import amisCss from "amis/lib/themes/default.css?raw";
    import {apiUrlWithoutTenants} from "override/utils/route";
    import {getCsrfToken} from "../../../utils/csrf";

    const props = defineProps<{appName: string; pageId: string}>();

    const container = ref<HTMLElement | null>(null);
    const loading = ref(true);
    const error = ref("");

    let root: Root | undefined;

    function ensureAmisStyle() {
        if (document.getElementById("dsh-amis-style")) return;
        const style = document.createElement("style");
        style.id = "dsh-amis-style";
        style.textContent = amisCss;
        document.head.appendChild(style);
    }

    const env: RenderOptions = {
        // dsh Apps requests go through the native fetch path with the same
        // cookie-authenticated session as the rest of the SPA: X-CSRF-TOKEN is injected
        // manually for non-safe methods (design docs/dsh-apps-amis.md §6 S1). The SDK's
        // useClient().request() currently hangs for relative app URLs, so we bypass it.
        fetcher: (api: Api, data?: unknown): Promise<Payload> => {
            const apiObject = typeof api === "string" ? {url: api} : api;
            const url = apiObject.url;
            const method = (apiObject.method ?? "get").toLowerCase();
            const body = data !== undefined ? data : apiObject.data;
            const headers: Record<string, string> = {"Content-Type": "application/json"};
            const csrf = getCsrfToken();
            if (csrf) headers["X-CSRF-TOKEN"] = csrf;
            return fetch(url, {
                method,
                headers,
                body: body !== undefined ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
                credentials: "include",
            }).then(async (resp) => {
                const text = await resp.text();
                let parsed: unknown = text;
                try {
                    parsed = text ? JSON.parse(text) : null;
                } catch {
                    // non-JSON body (e.g. plain error page) — keep the text
                }
                return {
                    ok: resp.ok,
                    status: resp.status,
                    data: parsed,
                    msg: resp.ok ? "" : `HTTP ${resp.status}`,
                    headers: resp.headers,
                };
            }).catch((err: unknown) => {
                return {
                    ok: false,
                    status: 500,
                    data: null,
                    msg: (err as Error).message ?? "Request failed",
                    headers: new Headers(),
                };
            });
        },
        notify: (type: string, msg: string) => {
            if (msg) {
                console.log(`[amis:${type}] ${msg}`);
            }
        },
        theme: "default",
    };

    async function load() {
        ensureAmisStyle();
        loading.value = true;
        error.value = "";
        try {
            const resp = await fetch(`${apiUrlWithoutTenants()}/apps/${props.appName}/${props.pageId}`, {
                headers: {"Accept": "application/json"},
                credentials: "include",
            });
            if (!resp.ok) {
                throw new Error(`App page returned HTTP ${resp.status}`);
            }
            const schema = await resp.json();
            await nextTick();
            if (!container.value) return;
            root?.unmount();
            const rendered = amisRender(schema as never, {}, env);
            if (!rendered || !(rendered as {$$typeof?: unknown}).$$typeof) {
                error.value = `Amis render produced no element (schema type: ${(schema as {type?: string})?.type ?? "unknown"})`;
                return;
            }
            root = createRoot(container.value);
            root.render(rendered);
        } catch (e) {
            const status = (e as {response?: {status?: number}}).response?.status;
            error.value = status === 404
                ? `App page "${props.appName}/${props.pageId}" not found`
                : `Failed to load app page: ${(e as Error).message ?? e}`;
        } finally {
            loading.value = false;
        }
    }

    onMounted(load);
    watch(() => [props.appName, props.pageId], load);

    onBeforeUnmount(() => {
        root?.unmount();
        root = undefined;
    });
</script>

<style scoped>
    .dsh-app-view {
        height: 100%;
        display: flex;
        flex-direction: column;
        overflow: hidden;
    }
    .dsh-app-view__status {
        padding: 24px;
        color: var(--bs-body-color);
        font-size: 14px;
    }
    .dsh-app-view__error {
        color: var(--bs-danger);
    }
    .dsh-app-view__container {
        flex: 1;
        overflow: auto;
        background: #fff;
    }
</style>
