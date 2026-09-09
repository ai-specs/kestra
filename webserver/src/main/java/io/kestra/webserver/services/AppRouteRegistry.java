package io.kestra.webserver.services;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.FlowInterface;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.queues.BroadcastQueueInterface;
import io.kestra.core.queues.QueueSubscriber;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowWithDefaultCache;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.services.FlowParsingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory route index for dsh Apps: maps {@code (tenant, kind, appName, pageId|apiId)}
 * to the declaring flow + trigger, so {@code /apps/{appName}/{...}} resolves without
 * scanning the flow repository per request (design docs/dsh-apps-amis.md §5.7).
 *
 * <p>Built at startup from {@link FlowRepositoryInterface#findAllForAllTenants()} and
 * kept in sync by subscribing to {@link BroadcastQueueInterface}{@code <FlowInterface>}
 * (same mechanism as {@link io.kestra.core.runners.DefaultFlowMetaStore}). A key with
 * more than one matching flow is ambiguous — callers must surface 409 with the conflict
 * list (route lookup never throws).
 *
 * <p>The trigger classes live in the standalone plugin jar ({@code plugin-dsh-apps}),
 * so the webserver only ever touches them through {@link AbstractTrigger} references
 * (safe to hold) and Jackson field reads by class-name match — no compile-time plugin
 * dependency, fields are resolved once at index time, not per request.
 */
@Singleton
public class AppRouteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AppRouteRegistry.class);

    public static final String PAGE_TRIGGER_CLASS = "io.kestra.plugin.dsh.apps.PageTrigger";
    public static final String API_TRIGGER_CLASS = "io.kestra.plugin.dsh.apps.ApiTrigger";

    public record PageRoute(Flow flow, AbstractTrigger trigger, String appName, String pageId, String amis) {
    }

    public record ApiRoute(Flow flow, AbstractTrigger trigger, String appName, String apiId,
                           String responseMode, Duration timeout) {
    }

    private static final String KIND_PAGE = "page";
    private static final String KIND_API = "api";

    // CopyOnWriteArrayList: the queue thread re-indexes (add/removeIf) while HTTP threads
    // iterate the same lists — a plain ArrayList risks ConcurrentModificationException.
    private final Map<String, List<PageRoute>> pageRoutes = new ConcurrentHashMap<>();
    private final Map<String, List<ApiRoute>> apiRoutes = new ConcurrentHashMap<>();

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private BroadcastQueueInterface<FlowInterface> flowQueue;

    @Inject
    private FlowParsingService flowParsingService;

    @Inject
    private FlowWithDefaultCache withDefaultCache;

    private QueueSubscriber<FlowInterface> subscriber;

    @PostConstruct
    void init() {
        flowRepository.findAllForAllTenants().forEach(this::index);
        this.subscriber = this.flowQueue.subscriber().subscribe(either -> {
            if (either.isRight()) {
                LOG.error("Unable to deserialize a flow event for AppRouteRegistry: {}", either.getRight().getMessage());
            } else {
                FlowInterface flow = either.getLeft();
                if (flow.isDeleted()) {
                    remove(flow);
                } else {
                    index(flow);
                }
                try {
                    this.withDefaultCache.invalidate(flow.uid());
                } catch (Exception e) {
                    LOG.debug("Unable to invalidate flow default cache for {}", flow.uid(), e);
                }
            }
        });
        LOG.info("AppRouteRegistry initialized: {} page routes, {} api routes",
            pageRoutes.size(), apiRoutes.size());
    }

    @PreDestroy
    void close() throws Exception {
        if (this.subscriber != null) {
            this.subscriber.close();
        }
    }

    private Flow resolveFlow(FlowInterface flowInterface) {
        if (flowInterface instanceof Flow flow) {
            return flow;
        }
        try {
            return flowParsingService.parse(flowInterface, false);
        } catch (Exception e) {
            LOG.warn("Unable to parse flow {} for AppRouteRegistry: {}", flowInterface.uid(), e.getMessage());
            return null;
        }
    }

    private void index(FlowInterface flowInterface) {
        Flow flow = resolveFlow(flowInterface);
        if (flow == null) {
            return;
        }
        remove(flowInterface);
        if (flow.getTriggers() == null) {
            return;
        }
        for (AbstractTrigger trigger : flow.getTriggers()) {
            String type = trigger.getType();
            if (PAGE_TRIGGER_CLASS.equals(type)) {
                Map<String, Object> fields = JacksonMapper.ofJson().convertValue(trigger, JacksonMapper.MAP_TYPE_REFERENCE);
                String appName = str(fields.get("appName"));
                String pageId = str(fields.get("pageId"));
                String amis = str(fields.get("amis"));
                if (appName == null || pageId == null || amis == null) {
                    LOG.warn("PageTrigger {} in flow {}/{} misses appName/pageId/amis", trigger.getId(), flow.getNamespace(), flow.getId());
                    continue;
                }
                pageRoutes.computeIfAbsent(key(KIND_PAGE, flow.getTenantId(), appName, pageId), k -> new CopyOnWriteArrayList<>())
                    .add(new PageRoute(flow, trigger, appName, pageId, amis));
            } else if (API_TRIGGER_CLASS.equals(type)) {
                Map<String, Object> fields = JacksonMapper.ofJson().convertValue(trigger, JacksonMapper.MAP_TYPE_REFERENCE);
                String appName = str(fields.get("appName"));
                String apiId = str(fields.get("apiId"));
                String responseMode = str(fields.get("responseMode"));
                if (appName == null || apiId == null) {
                    LOG.warn("ApiTrigger {} in flow {}/{} misses appName/apiId", trigger.getId(), flow.getNamespace(), flow.getId());
                    continue;
                }
                Duration timeout = Duration.ofSeconds(30);
                Object rawTimeout = fields.get("timeout");
                if (rawTimeout instanceof String s) {
                    try {
                        timeout = Duration.parse(s);
                    } catch (Exception ignored) {
                    }
                }
                apiRoutes.computeIfAbsent(key(KIND_API, flow.getTenantId(), appName, apiId), k -> new CopyOnWriteArrayList<>())
                    .add(new ApiRoute(flow, trigger, appName, apiId, responseMode, timeout));
            }
        }
    }

    private void remove(FlowInterface flowInterface) {
        pageRoutes.values().forEach(list -> list.removeIf(r -> sameFlow(r.flow(), flowInterface)));
        pageRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
        apiRoutes.values().forEach(list -> list.removeIf(r -> sameFlow(r.flow(), flowInterface)));
        apiRoutes.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean sameFlow(Flow flow, FlowInterface other) {
        return Objects.equals(flow.getTenantId(), other.getTenantId())
            && Objects.equals(flow.getNamespace(), other.getNamespace())
            && Objects.equals(flow.getId(), other.getId());
    }

    private static String key(String kind, String tenant, String appName, String id) {
        // OSS stores flows without a tenant (null) while TenantService.resolveTenant() always
        // returns "main" — normalize both sides to the same key or null-tenant flows become
        // invisible to route lookups.
        return (tenant == null ? "main" : tenant) + "|" + kind + "|" + appName + "|" + id;
    }

    public List<PageRoute> pageRoutes(String tenant, String appName, String pageId) {
        return pageRoutes.getOrDefault(key(KIND_PAGE, tenant, appName, pageId), List.of());
    }

    public List<ApiRoute> apiRoutes(String tenant, String appName, String apiId) {
        return apiRoutes.getOrDefault(key(KIND_API, tenant, appName, apiId), List.of());
    }
}
