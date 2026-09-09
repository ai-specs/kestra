package io.kestra.webserver.controllers.api;

import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.State;
import io.kestra.core.storages.Namespace;
import io.kestra.core.storages.NamespaceFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.webserver.services.AppRouteRegistry;
import io.kestra.webserver.services.AppsService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * dsh Apps HTTP routes (design docs/dsh-apps-amis.md §3/§5.7):
 *
 * <ul>
 *   <li>{@code GET  /api/v1/apps/{appName}/{pageId}} — serve the Amis schema declared by a PageTrigger.</li>
 *   <li>{@code POST /api/v1/apps/{appName}/{apiId}} — create an execution of the declaring flow from the request body.</li>
 *   <li>{@code GET  /api/v1/apps/{appName}/{apiId}/executions/{executionId}} — poll the execution state/outputs.</li>
 * </ul>
 *
 * <p>Authentication is the default /api/v1 one (OIDC session or Bearer, enforced by the
 * platform filters — no {@code @AnonymousAccess} anywhere), and POSTs additionally require
 * the platform CSRF token, exactly like the rest of the UI API. Route resolution is served
 * by {@link AppRouteRegistry}; an ambiguous key (declared by several flows) is a 409.
 */
@Controller("/api/v1/apps")
@Singleton
@ExecuteOn(TaskExecutors.IO)
@Slf4j
public class AppRouterController {

    @Inject
    private AppRouteRegistry routeRegistry;

    @Inject
    private AppsService appsService;

    @Inject
    private TenantService tenantService;

    @Inject
    private NamespaceFactory namespaceFactory;

    @Inject
    private StorageInterface storageInterface;

    /**
     * GET /api/v1/apps — aggregate list of every app (name, namespace, pages, apis)
     * visible to the current tenant, for the "应用程序" list page.
     */
    @Get(uri = "/")
    @Operation(summary = "List all dsh apps")
    public HttpResponse<List<AppRouteRegistry.AppSummary>> apps() {
        String tenant = tenantService.resolveTenant();
        return HttpResponse.ok(routeRegistry.apps(tenant));
    }

    /**
     * GET /api/v1/apps/{appName}/{pageId} — resolve the PageTrigger and return its Amis schema.
     */
    @Get(uri = "/{appName}/{pageId}")
    @Operation(summary = "Resolve an app page route and return its Amis schema")
    public HttpResponse<?> page(
        @PathVariable String appName,
        @PathVariable String pageId
    ) {
        String tenant = tenantService.resolveTenant();
        List<AppRouteRegistry.PageRoute> routes = routeRegistry.pageRoutes(tenant, appName, pageId);
        AppRouteRegistry.PageRoute route = resolveUnique(routes, "page", appName + "/" + pageId);
        Flow flow = executableFlow(route.flow());
        executableTrigger(route.trigger());

        // nsfile:///path — three slashes pin to the declaring flow's own namespace (no parent inheritance).
        String amisUri = route.amis();
        URI uri;
        try {
            uri = URI.create(amisUri);
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid amis URI: " + amisUri);
        }
        if (!"nsfile".equals(uri.getScheme()) || uri.getAuthority() != null || uri.getPath() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                "Only nsfile:/// URIs inside the flow's own namespace are supported (got " + amisUri + ")");
        }

        try {
            Namespace ns = namespaceFactory.of(tenant, flow.getNamespace(), storageInterface);
            try (InputStream in = ns.getFileContent(java.nio.file.Path.of(uri.getPath()), null)) {
                String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return HttpResponse.ok(schema).contentType(MediaType.APPLICATION_JSON_TYPE);
            }
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND,
                "App page schema not found for " + appName + "/" + pageId + " (" + amisUri + "): " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/apps/{appName}/{apiId} — create an execution of the declaring flow.
     */
    @Post(uri = "/{appName}/{apiId}", consumes = MediaType.APPLICATION_JSON)
    @Operation(summary = "Create an execution for an app api route")
    @ApiResponse(responseCode = "202", description = "Execution created (ASYNC, or SYNC degraded to polling)")
    @ApiResponse(responseCode = "200", description = "Execution finished (SYNC)", content = @Content(schema = @Schema(implementation = Map.class)))
    public HttpResponse<?> api(
        @PathVariable String appName,
        @PathVariable String apiId,
        @Body Map<String, Object> body
    ) {
        String tenant = tenantService.resolveTenant();
        List<AppRouteRegistry.ApiRoute> routes = routeRegistry.apiRoutes(tenant, appName, apiId);
        AppRouteRegistry.ApiRoute route = resolveUnique(routes, "api", appName + "/" + apiId);
        Flow flow = executableFlow(route.flow());
        // Disabled is checked at route-activation time only: the polling endpoint below
        // keeps serving in-flight executions when a trigger is disabled mid-run, matching
        // the webhook contract (disabled rejects execution creation, not status reads).
        executableTrigger(route.trigger());

        boolean sync = "SYNC".equals(route.responseMode());
        Duration timeout = route.timeout() != null ? route.timeout() : Duration.ofSeconds(30);

        // apiId is trusted from the ApiTrigger declaration (the route is the source of
        // truth): inject it into the execution inputs unconditionally, overriding any
        // client-supplied value. Multi-API flows branch on {{ inputs.apiId }} via a
        // core.flow.Switch; single-API flows simply ignore the extra input.
        Map<String, Object> inputs = body == null ? new HashMap<>() : new HashMap<>(body);
        inputs.put("apiId", apiId);

        Optional<Execution> maybeExecution = appsService.createExecution(flow, route.trigger(), inputs);
        if (maybeExecution.isEmpty()) {
            // trigger conditions not met — same contract as webhooks
            return HttpResponse.status(HttpStatus.NO_CONTENT);
        }
        Execution execution = maybeExecution.get();

        try {
            appsService.startExecution(execution).block();
        } catch (Exception e) {
            log.error("Unable to start execution {} for app api {}/{}", execution.getId(), appName, apiId, e);
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to start execution: " + e.getMessage());
        }

        if (!sync) {
            // ASYNC: 202 + body {executionId, executionState, executionUrl, ...} + Location
            // header (same value) so the client knows WHERE to poll from the response
            // itself (self-describing; no out-of-band URL construction rule).
            return HttpResponse.status(HttpStatus.ACCEPTED)
                .header("Location", executionUrl(appName, apiId, execution.getId()))
                .body(stateBody(execution, null, null, route.responseBody(), null, appName, apiId,
                    executionUrl(appName, apiId, execution.getId())));
        }

        // SYNC: wait for a terminal state (or PAUSED), bounded by the trigger's timeout; degrade to 202 on timeout.
        State.Type terminal = awaitTerminal(execution, flow, timeout);
        if (terminal == null || terminal == State.Type.PAUSED) {
            return HttpResponse.status(HttpStatus.ACCEPTED)
                .header("Location", executionUrl(appName, apiId, execution.getId()))
                .body(stateBody(execution, null, null, route.responseBody(), terminal, appName, apiId,
                    executionUrl(appName, apiId, execution.getId())));
        }

        Map<String, Object> outputs = null;
        String error = null;
        if (terminal == State.Type.SUCCESS || terminal == State.Type.WARNING) {
            try {
                outputs = awaitOutputs(execution.getTenantId(), execution.getNamespace(), execution.getFlowId(), execution.getId());
            } catch (Exception e) {
                error = "Unable to read execution outputs: " + e.getMessage();
            }
        } else {
            error = "Execution ended with state " + terminal;
        }
        return HttpResponse.ok(stateBody(execution, outputs, error, route.responseBody(), terminal, appName, apiId,
            executionUrl(appName, apiId, execution.getId())));
    }

    /**
     * Execution outputs are persisted asynchronously after the execution terminates
     * (written onto the execution record by the executor once the flow finishes), so a
     * SYNC caller — or a poll right after the terminal event — can observe empty outputs
     * for a short window. Re-fetch the execution from the repository on each round
     * (the in-memory terminal event may predate the outputs write) and poll briefly,
     * bounded and best-effort.
     */
    private Map<String, Object> awaitOutputs(String tenant, String namespace, String flowId, String executionId) throws Exception {
        Map<String, Object> outputs = null;
        for (int i = 0; i < 20; i++) {
            Optional<Execution> fresh = appsService.findScopedExecution(tenant, namespace, flowId, executionId);
            if (fresh.isEmpty()) {
                return null;
            }
            outputs = appsService.executionOutputs(fresh.get());
            if (outputs != null && !outputs.isEmpty()) {
                return outputs;
            }
            Thread.sleep(250);
        }
        return outputs;
    }

    /**
     * GET /api/v1/apps/{appName}/{apiId}/executions/{executionId} — poll state/outputs, scoped to the matched flow.
     */
    @Get(uri = "/{appName}/{apiId}/executions/{executionId}")
    @Operation(summary = "Poll an app api execution state and outputs")
    public HttpResponse<?> status(
        @PathVariable String appName,
        @PathVariable String apiId,
        @PathVariable String executionId
    ) {
        String tenant = tenantService.resolveTenant();
        List<AppRouteRegistry.ApiRoute> routes = routeRegistry.apiRoutes(tenant, appName, apiId);
        AppRouteRegistry.ApiRoute route = resolveUnique(routes, "api", appName + "/" + apiId);
        Flow flow = executableFlow(route.flow());

        Optional<Execution> maybe = appsService.findScopedExecution(tenant, flow.getNamespace(), flow.getId(), executionId);
        if (maybe.isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        }
        Execution execution = maybe.get();

        if (!execution.getState().isTerminated()) {
            // Polling endpoint: no executionUrl — the client is already on that URL.
            return HttpResponse.ok(stateBody(execution, null, null, route.responseBody(), null, appName, apiId, null));
        }

        Map<String, Object> outputs = null;
        String error = null;
        State.Type current = execution.getState().getCurrent();
        if (current == State.Type.SUCCESS || current == State.Type.WARNING) {
            try {
                outputs = awaitOutputs(execution.getTenantId(), execution.getNamespace(), execution.getFlowId(), execution.getId());
            } catch (Exception e) {
                error = "Unable to read execution outputs: " + e.getMessage();
            }
        } else {
            error = "Execution ended with state " + current;
        }
        return HttpResponse.ok(stateBody(execution, outputs, error, route.responseBody(), current, appName, apiId, null));
    }

    /**
     * Follow the execution event stream until a terminal state (or PAUSED), bounded by the timeout.
     *
     * @return the state that ended the wait, or null when the timeout elapsed without one
     */
    private State.Type awaitTerminal(Execution execution, Flow flow, Duration timeout) {
        try {
            Flux<Event<Execution>> events = appsService.followExecution(execution, flow);
            return events
                .map(Event::getData)
                .filter(Objects::nonNull)
                .map(Execution::getState)
                .map(State::getCurrent)
                .filter(t -> t != null && (t.isTerminated() || t == State.Type.PAUSED))
                .next()
                .block(timeout);
        } catch (Exception e) {
            log.warn("SYNC app api wait interrupted for execution {}: {}", execution.getId(), e.getMessage());
            return null;
        }
    }

    private static <T> T resolveUnique(List<T> routes, String kind, String route) {
        if (routes.isEmpty()) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "No " + kind + " route registered for " + route);
        }
        if (routes.size() > 1) {
            throw new HttpStatusException(HttpStatus.CONFLICT,
                "Ambiguous " + kind + " route " + route + ": declared by multiple flows — " +
                    routes.stream()
                        .map(r -> r instanceof AppRouteRegistry.PageRoute p ? p.flow().getNamespace() + "." + p.flow().getId()
                            : r instanceof AppRouteRegistry.ApiRoute a ? a.flow().getNamespace() + "." + a.flow().getId()
                            : "?")
                        .toList()
            );
        }
        return routes.get(0);
    }

    private static Flow executableFlow(Flow flow) {
        if (flow.isDisabled()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Cannot execute app route: flow is disabled.");
        }
        return flow;
    }

    private static AbstractTrigger executableTrigger(AbstractTrigger trigger) {
        if (trigger.isDisabled()) {
            throw new HttpStatusException(HttpStatus.CONFLICT,
                "Cannot serve app route: the trigger '%s' is disabled.".formatted(trigger.getId()));
        }
        return trigger;
    }

    private static Map<String, Object> stateBody(Execution execution, Map<String, Object> outputs, String error, String responseBody,
                                                 State.Type stateOverride, String appName, String apiId, String executionUrl) {
        String stateName = stateOverride != null
            ? stateOverride.name()
            : (execution.getState().getCurrent() == null ? "CREATED" : execution.getState().getCurrent().name());
        // Self-described polling URL (HATEOAS-style): only the FIRST response (POST)
        // tells the client WHERE to poll; the polling endpoint's own responses do not
        // carry it (the client is already on that URL — a self-reference adds nothing).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", execution.getId());
        body.put("executionState", stateName);
        if (executionUrl != null) {
            body.put("executionUrl", executionUrl);
        }
        body.put("outputs", outputs);
        body.put("error", error);
        // AMIS: amis standard payload {status, msg, data} plus top-level executionId,
        // executionState and (on POST only) executionUrl (all ignored by amis, but they
        // let an ASYNC client poll and tell a running execution from a finished one).
        // data is always an object (the outputs map, or {} when there is nothing yet) —
        // never null or "" — and amis requires a key-value structure:
        //   - still running : status 0, msg "", data: {}
        //   - success        : status 0, msg "", data: <outputs>
        //   - failure        : status 2, msg: <error>, msgTimeout: 10000, data: {}
        if ("AMIS".equals(responseBody)) {
            Map<String, Object> amis = new LinkedHashMap<>();
            amis.put("executionId", execution.getId());
            amis.put("executionState", stateName);
            if (executionUrl != null) {
                amis.put("executionUrl", executionUrl);
            }
            if (error != null) {
                amis.put("status", 2);
                amis.put("msg", error);
                amis.put("msgTimeout", 10000);
            } else {
                amis.put("status", 0);
                amis.put("msg", "");
            }
            amis.put("data", outputs == null ? Map.of() : outputs);
            return amis;
        }
        return body;
    }

    private static String executionUrl(String appName, String apiId, String executionId) {
        return "/api/v1/apps/" + appName + "/" + apiId + "/executions/" + executionId;
    }
}
