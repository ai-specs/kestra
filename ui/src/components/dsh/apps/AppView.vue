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

    // App api POSTs return 202 {executionId, state} (ASYNC); poll the status endpoint
    // until the execution reaches a terminal state, then hand the final payload
    // {executionId, state, outputs} back to amis so the form result reaches the page.
    // This is the "send → result comes back" half of the browser MVP (design §5.7).
    const POLL_INTERVAL_MS = 1000;
    const POLL_TIMEOUT_MS = 30000;
    const TERMINAL_STATES = new Set(["SUCCESS", "FAILED", "KILLED", "WARNING"]);

    async function pollExecution(pollUrl: string): Promise<unknown> {
        const deadline = Date.now() + POLL_TIMEOUT_MS;
        // eslint-disable-next-line no-constant-condition
        while (true) {
            const r = await fetch(pollUrl, {
                headers: {"Accept": "application/json"},
                credentials: "include",
            });
            if (r.ok) {
                const d = await r.json().catch(() => null);
                const state = (d as {state?: string} | null)?.state;
                if (state && TERMINAL_STATES.has(state)) {
                    return d;
                }
            }
            if (Date.now() >= deadline) {
                return null; // give up polling; amis still gets the 202 payload below
            }
            await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
        }
    }

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
                // dsh Apps: POST to an app api returns 202 {executionId} — wait for the
                // terminal state and replace the payload with the final {state, outputs}.
                if (method === "post" && resp.ok && /\/api\/v1\/apps\/[^/]+\/[^/]+$/.test(url)) {
                    const executionId = (parsed as {executionId?: string} | null)?.executionId;
                    if (executionId) {
                        const polled = await pollExecution(`${url}/executions/${executionId}`);
                        if (polled !== null) {
                            parsed = polled;
                        }
                    }
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
        /* standalone page: fills the viewport, no Kestra shell around it */
        min-height: 100vh;
        width: 100%;
        background: #fff;
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
        min-height: 100vh;
        overflow: auto;
        background: #fff;
    }
</style>
