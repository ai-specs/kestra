package io.kestra.relay.controllers;

import io.kestra.relay.DshIdentity;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Instant;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * dsh 中台转发层（选项 B：边端权威 + 中台纯转发）——纯内存转发，不落库。
 *
 * <p>
 * 职责（docs/dsh-pc-bridge.md §5、docs/implementation-roadmap.md 阶段 1）：
 * <ul>
 *   <li><b>REST 转发端点</b>（§5.2）：Phone→PC 指令（relay/input）、PC→Phone 结果
 *       （relay/result）、审批请求（relay/approval）与审批决策（relay/approval/decide）；</li>
 *   <li><b>SSE 事件流</b>（§5.3）：GET relay/events 按 {@code sub} 隔离，同一用户
 *       PC（client_id=dsh-pc）与 Phone（client_id=dsh-ui）各一条连接；</li>
 *   <li><b>在线状态注册表</b>（§5.1）：PC 在线 = SSE 连接存在 <b>且</b> 应用层心跳
 *       未超时（方案 A'，2026-09-27：PC 每 30s POST /heartbeat，90s 无心跳即半开
 *       离线——TCP keepalive 对 SSE 心跳连接不生效，行为级测试 relay-keepalive-test.sh
 *       实证）；Phone 在线 = SSE 连接存在；</li>
 *   <li><b>短暂缓存</b>（§5.4）：转发目标离线时消息缓存 60s，目标上线（SSE 重连）
 *       后立即补推。</li>
 * </ul>
 *
 * <p>
 * 跨用户隔离（dsh.docx 跨端同步原理）：所有转发只发生在前缀相同的同一 {@code sub}
 * 两侧（PC ↔ Phone）；不同用户互不可见。服务身份（client_credentials，sub=client_id）
 * 不参与转发（没有人类对端），一律 403。
 */
@Controller("/api/v1/dsh/relay")
public class DshRelayController {

    /** 转发目标离线时消息缓存 60s（§5.4）。 */
    private static final long CACHE_TTL_MS = 60_000;
    /** 查询结果缓存 TTL（§5.2 查询转发：Phone 轮询取结果窗口）。 */
    private static final long QUERY_TTL_MS = 30_000;
    /** SSE 保活间隔（§5.3：heartbeat 15s）。仅供防 PC 侧 45s idle guard 断连；不参与判活。 */
    private static final long HEARTBEAT_MS = 15_000;
    /**
     * PC 应用层活性判死超时（方案 A'，2026-09-27）：PC 每 30s POST /heartbeat，
     * 超过 90s（3 倍心跳周期裕量）未收到 → 视为半开离线。TCP keepalive 经行为级
     * 测试（relay-keepalive-test.sh）实证对 SSE 心跳连接不生效（出站心跳持续刷新
     * 内核 idle 计数），判死必须由应用层心跳承担；SSE heartbeat 与判活解耦。
     */
    private static final long PC_HEARTBEAT_TIMEOUT_MS = 90_000;
    /** Per-sub 宽松限流：滑动窗口 10s 内最多请求数（2026-09-27 异常兜底）。 */
    private static final int RATE_LIMIT_PER_WINDOW = 60;
    /** Per-sub 限流滑动窗口时长（毫秒）。 */
    private static final long RATE_LIMIT_WINDOW_MS = 10_000;

    /** PC 端 client_id（dsh-pc，PKCE 用户身份）。 */
    private static final String CLIENT_PC = "dsh-pc";
    /** Phone 端 client_id（dsh-ui，PKCE 用户身份）。 */
    private static final String CLIENT_PHONE = "dsh-ui";

    /** sub → PC 端 SSE sink。 */
    private final ConcurrentMap<String, FluxSink<RelayEvent>> pcSinks = new ConcurrentHashMap<>();
    /** sub → Phone 端 SSE sink。 */
    private final ConcurrentMap<String, FluxSink<RelayEvent>> phoneSinks = new ConcurrentHashMap<>();
    /** sub → PC 最近一次应用层心跳时刻（epoch ms；方案 A' 判活依据，非 SSE 心跳）。 */
    private final ConcurrentMap<String, Long> pcHeartbeats = new ConcurrentHashMap<>();
    /** sub → 在线状态（SSE 连接即在线；role 由 client_id 判定）。 */
    private final ConcurrentMap<String, Map<String, Boolean>> presence = new ConcurrentHashMap<>();
    /** msgId → 未送达消息（60s TTL，目标上线补推）。 */
    private final ConcurrentMap<String, CachedMessage> cache = new ConcurrentHashMap<>();
    /** requestId → 查询结果（§5.2 查询转发：PC 回填，Phone 轮询取走）。 */
    private final ConcurrentMap<String, QueryResult> queryResults = new ConcurrentHashMap<>();
    /** requestId → 已受理但可能尚未回填的查询；用于区分 202 pending 与真正的 404。 */
    private final ConcurrentMap<String, PendingQuery> pendingQueries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Map<String, Object>>> queryWaiters = new ConcurrentHashMap<>();
    /** sub → 限流滑动窗口（请求时间戳队列；每 30s purgeCache 顺带清理空桶）。 */
    private final ConcurrentMap<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dsh-relay");
        t.setDaemon(true);
        return t;
    });

    public DshRelayController() {
        // 每 30s：清理过期缓存；每 15s：SSE 保活心跳
        scheduler.scheduleAtFixedRate(this::purgeCache, 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    // ── 请求/事件记录 ────────────────────────────────────────────────────────

    /** Phone → PC：App 发起新会话或追问。 */
    public record RelayInput(String text, String sessionId, Boolean newSession, String workspaceId) {}

    /** PC → Phone：执行结果。 */
    public record RelayResult(String sessionId, String phase, Object result) {}

    /** PC → Phone：审批请求（PC Agent 挂起待审批）。 */
    public record RelayApproval(String sessionId, String type, Object payload, String summary) {}

    /** Phone → PC：审批决策。 */
    public record RelayDecision(String sessionId, Boolean approved, String comment) {}

    /** Phone → PC：会话查询（列表/详情）。requestId 由 Phone 生成，PC 回填时原样携带。 */
    public record RelayQuery(String requestId, String type, String sessionId, String since) {}

    /** PC → 中台：查询结果回填（type 为 session.list/session.detail；payload 为结果 JSON；error 为查询失败说明）。 */
    public record RelayQueryResult(String requestId, String type, Object payload, String error) {}

    /** 无 SSE 形态（daemon 等）的在线状态心跳。 */
    public record PresenceBody(Boolean online) {}

    /** SSE 事件载荷（§5.3：event 类型 + 负载；与 Kestra follow 同为 JSON 序列化）。 */
    public record RelayEvent(String event, String id, Map<String, Object> data) {}

    /** 一条待补推的未送达消息。 */
    private record CachedMessage(String toSub, String role, String type, Map<String, Object> data, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /** 一条查询结果（PC 回填后供 Phone 轮询取走，TTL 30s）。 */
    private record QueryResult(String toSub, Map<String, Object> payload, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /** 一条已成功投递给 PC、等待结果的查询。 */
    private record PendingQuery(String sub, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    // ── REST 转发端点（§5.2）────────────────────────────────────────────────

    /**
     * Phone → PC：把 App 发起的指令实时转发给同用户的 PC（SSE session.input）。
     * PC 在线 → delivered；PC 离线 → 缓存 60s 待 PC 上线补推（§5.4），返回 cached。
     */
    @Post("/input")
    @Operation(summary = "Relay a Phone input to the same user's PC (option B, stateless forward)")
    public HttpResponse<Map<String, Object>> input(
        HttpRequest<?> request,
        @Body RelayInput body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "text is required"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", body.text());
        data.put("sessionId", body.sessionId());
        data.put("newSession", body.newSession() == null ? Boolean.FALSE : body.newSession());
        if (body.workspaceId() != null && !body.workspaceId().isBlank()) {
            data.put("workspaceId", body.workspaceId());
        }
        boolean delivered = deliver(caller.sub(), CLIENT_PC, "session.input", data);
        if (delivered) {
            return HttpResponse.ok(Map.of("delivered", true));
        }
        cacheMessage(caller.sub(), CLIENT_PC, "session.input", data);
        return HttpResponse.ok(Map.of("delivered", false, "cached", true, "reason", "pc offline"));
    }

    /**
     * PC → Phone：执行结果（SSE session.result）。Phone 在线 → delivered；
     * 离线 → 缓存 60s 待 Phone 上线补推。
     */
    @Post("/result")
    @Operation(summary = "Relay a PC execution result to the same user's Phone (option B)")
    public HttpResponse<Map<String, Object>> result(
        HttpRequest<?> request,
        @Body RelayResult body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.sessionId() == null || body.sessionId().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "sessionId is required"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", body.sessionId());
        data.put("phase", body.phase());
        data.put("result", body.result());
        boolean delivered = deliver(caller.sub(), CLIENT_PHONE, "session.result", data);
        if (delivered) {
            return HttpResponse.ok(Map.of("delivered", true));
        }
        cacheMessage(caller.sub(), CLIENT_PHONE, "session.result", data);
        return HttpResponse.ok(Map.of("delivered", false, "cached", true, "reason", "phone offline"));
    }

    /** PC → Phone：审批请求（SSE session.approval）。 */
    @Post("/approval")
    @Operation(summary = "Relay an approval request from PC to the same user's Phone (option B)")
    public HttpResponse<Map<String, Object>> approval(
        HttpRequest<?> request,
        @Body RelayApproval body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.sessionId() == null || body.sessionId().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "sessionId is required"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", body.sessionId());
        data.put("type", body.type());
        data.put("payload", body.payload());
        data.put("summary", body.summary());
        boolean delivered = deliver(caller.sub(), CLIENT_PHONE, "session.approval", data);
        if (delivered) {
            return HttpResponse.ok(Map.of("delivered", true));
        }
        cacheMessage(caller.sub(), CLIENT_PHONE, "session.approval", data);
        return HttpResponse.ok(Map.of("delivered", false, "cached", true, "reason", "phone offline"));
    }

    /** Phone → PC：审批决策（SSE session.approval.decision）。 */
    @Post("/approval/decide")
    @Operation(summary = "Relay an approval decision from Phone to the same user's PC (option B)")
    public HttpResponse<Map<String, Object>> decide(
        HttpRequest<?> request,
        @Body RelayDecision body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.sessionId() == null || body.sessionId().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "sessionId is required"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", body.sessionId());
        data.put("approved", body.approved());
        data.put("comment", body.comment());
        boolean delivered = deliver(caller.sub(), CLIENT_PC, "session.approval.decision", data);
        if (delivered) {
            return HttpResponse.ok(Map.of("delivered", true));
        }
        cacheMessage(caller.sub(), CLIENT_PC, "session.approval.decision", data);
        return HttpResponse.ok(Map.of("delivered", false, "cached", true, "reason", "pc offline"));
    }

    /**
     * Phone → PC：会话查询转发（选项 B「Phone 经通道从 PC 拉取会话」）。
     * PC 在线 → 经 SSE 推 {@code session.query}，返回 202 pending；Phone 随后轮询
     * {@code GET /relay/query-result/{requestId}} 取 PC 回填的结果。
     * PC 离线 → 200 {@code offline:true}，Phone 回退本地缓存（可能滞后）。
     */
    @Post("/query")
    @Operation(summary = "Relay a session query (list/detail) from Phone to the same user's PC (option B)")
    public HttpResponse<Map<String, Object>> query(
        HttpRequest<?> request,
        @Body RelayQuery body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.requestId() == null || body.requestId().isBlank()
            || body.type() == null || body.type().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "requestId and type are required"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", body.requestId());
        data.put("type", body.type());
        data.put("sessionId", body.sessionId());
        if (body.since() != null && !body.since().isBlank()) {
            // session.list 时间线增量：只向 PC 转发 updatedAt 晚于 since 的会话。
            data.put("since", body.since());
        }
        // 先登记再投递：SSE sink.next 后 PC 可能极快回填，反序会让合法结果被误判 unknown。
        pendingQueries.put(body.requestId(), new PendingQuery(
            caller.sub(),
            System.currentTimeMillis() + QUERY_TTL_MS
        ));
        boolean delivered = deliver(caller.sub(), CLIENT_PC, "session.query", data);
        if (delivered) {
            return HttpResponse.ok(Map.of("pending", true));
        }
        pendingQueries.remove(body.requestId());
        return HttpResponse.ok(Map.of("offline", true, "reason", "pc offline"));
    }

    /** PC → 中台：查询结果回填（缓存 30s 供 Phone 轮询；Phone 在线时同时推送 SSE）。 */
    @Post("/query-result")
    @Operation(summary = "PC fills a session query result back to the relay cache (option B)")
    public HttpResponse<Map<String, Object>> queryResult(
        HttpRequest<?> request,
        @Body RelayQueryResult body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (body == null || body.requestId() == null || body.requestId().isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "requestId is required"));
        }
        PendingQuery pending = pendingQueries.get(body.requestId());
        if (pending == null || pending.expired()) {
            pendingQueries.remove(body.requestId());
            return HttpResponse.status(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(Map.of("error", "query expired or unknown"));
        }
        if (!pending.sub().equals(caller.sub())) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of("error", "query belongs to another user"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", body.requestId());
        payload.put("type", body.type());
        if (body.error() != null && !body.error().isBlank()) {
            payload.put("error", body.error());
        } else {
            payload.put("payload", body.payload());
        }
        queryResults.put(body.requestId(), new QueryResult(caller.sub(), payload, System.currentTimeMillis() + QUERY_TTL_MS));
        CompletableFuture<Map<String, Object>> waiter = queryWaiters.remove(body.requestId());
        if (waiter != null) waiter.complete(payload);
        // Phone 在线时同步推送（App 现以轮询为主，此推送为可选的实时增强）。
        deliver(caller.sub(), CLIENT_PHONE, "session.query.result", payload);
        return HttpResponse.ok(Map.of("ok", true));
    }

    @Get("/sessions/{sessionId}/messages{?afterSeq}")
    @Operation(summary = "Read incremental messages from the same user's PC session")
    public CompletableFuture<HttpResponse<Map<String, Object>>> messages(
        HttpRequest<?> request,
        String sessionId,
        Integer afterSeq
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) return CompletableFuture.completedFuture(forbidden());
        if (rateLimited(caller.sub())) return CompletableFuture.completedFuture(tooManyRequests());
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
        queryWaiters.put(requestId, waiter);
        pendingQueries.put(requestId, new PendingQuery(caller.sub(), System.currentTimeMillis() + QUERY_TTL_MS));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("type", "session.messages");
        data.put("sessionId", sessionId);
        data.put("afterSeq", Math.max(0, afterSeq == null ? 0 : afterSeq));
        if (!deliver(caller.sub(), CLIENT_PC, "session.query", data)) {
            queryWaiters.remove(requestId);
            pendingQueries.remove(requestId);
            return CompletableFuture.completedFuture(HttpResponse.ok(Map.of("offline", true)));
        }
        return waiter.orTimeout(6, TimeUnit.SECONDS).handle((payload, error) -> {
            queryWaiters.remove(requestId);
            pendingQueries.remove(requestId);
            queryResults.remove(requestId);
            if (error != null) return HttpResponse.status(io.micronaut.http.HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "PC message query timeout"));
            if (payload.get("error") != null) return HttpResponse.status(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(Map.of("error", payload.get("error")));
            Object body = payload.get("payload");
            if (body instanceof Map<?, ?> map) {
                Map<String, Object> response = new LinkedHashMap<>();
                map.forEach((key, value) -> response.put(String.valueOf(key), value));
                return HttpResponse.ok(response);
            }
            return HttpResponse.ok(Map.of());
        });
    }

    /** Phone 轮询取查询结果（等待中 202；就绪 200 且取走即删；真正未知/过期 404）。 */
    @Get("/query-result/{requestId}")
    @Operation(summary = "Phone polls a relayed session query result (option B)")
    public HttpResponse<Map<String, Object>> queryResultPoll(
        HttpRequest<?> request,
        String requestId
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        QueryResult result = queryResults.get(requestId);
        if (result != null && !result.expired()) {
            if (!result.toSub().equals(caller.sub())) {
                return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "query result belongs to another user"));
            }
            queryResults.remove(requestId);
            pendingQueries.remove(requestId);
            return HttpResponse.ok(result.payload());
        }
        queryResults.remove(requestId);

        PendingQuery pending = pendingQueries.get(requestId);
        if (pending == null || pending.expired()) {
            pendingQueries.remove(requestId);
            return HttpResponse.status(io.micronaut.http.HttpStatus.NOT_FOUND)
                .body(Map.of("error", "query expired or unknown"));
        }
        if (!pending.sub().equals(caller.sub())) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of("error", "query result belongs to another user"));
        }
        return HttpResponse.status(io.micronaut.http.HttpStatus.ACCEPTED)
            .body(Map.of("pending", true));
    }

    /**
     * 无 SSE 形态（daemon 等）的在线心跳（§5.1）：记录 presence 备用。
     * 主形态仍是 SSE 连接即在线；此端点是 daemon 形态的补充，供后续启用。
     */
    @Post("/presence")
    @Operation(summary = "Report online/offline presence for non-SSE daemon clients (option B)")
    public HttpResponse<Map<String, Object>> presence(
        HttpRequest<?> request,
        @Body PresenceBody body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        boolean online = body == null || body.online() == null || body.online();
        presence.compute(caller.sub(), (k, v) -> {
            Map<String, Boolean> m = v == null ? new ConcurrentHashMap<>() : v;
            m.put(roleOf(caller), online);
            return m;
        });
        return HttpResponse.ok(Map.of("sub", caller.sub(), "role", roleOf(caller), "online", online));
    }

    /**
     * PC 应用层活性心跳（方案 A'，2026-09-27）：PC 每 30s POST 一次，relay 以
     * {@code PC_HEARTBEAT_TIMEOUT_MS}=90s 超时判 PC 半开离线（PC 断电/死机后心跳
     * 停更）。只接受 PC 角色（client_id=dsh-pc）——手机端上报一律 403，防止
     * 手机端"复活"PC。SSE 心跳（relay→PC 15s）与判活解耦，不参与本判定。
     */
    @Post("/heartbeat")
    @Operation(summary = "PC application-level liveness heartbeat (option A': half-open detection by heartbeat timeout)")
    public HttpResponse<Map<String, Object>> heartbeat(
        HttpRequest<?> request,
        @Body Map<String, Object> body
    ) {
        DshIdentity.Principal caller = userOnly(request);
        if (caller == null) {
            return forbidden();
        }
        if (rateLimited(caller.sub())) {
            return tooManyRequests();
        }
        if (!CLIENT_PC.equals(roleOf(caller))) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .body(Map.of("error", "heartbeat is PC-only; phone must not resurrect a PC"));
        }
        pcHeartbeats.put(caller.sub(), System.currentTimeMillis());
        return HttpResponse.ok(Map.of("sub", caller.sub(), "accepted", true));
    }

    // ── SSE 事件流（§5.3）────────────────────────────────────────────────────

    /**
     * 订阅本用户的中台事件流。按 {@code client_id} 判定角色：dsh-pc → 收
     * session.input / session.approval.decision；dsh-ui → 收 session.result /
     * session.approval / pc.status。连接建立 = 在线（注册表），断开 = 离线；
     * 离线期间缓存的消息（§5.4）在连接建立时补推。
     */
    @Get(value = "/events", produces = MediaType.TEXT_EVENT_STREAM)
    @Operation(summary = "Subscribe to the same-user relay event stream (SSE, sub-isolated)")
    public Flux<RelayEvent> events(
        HttpRequest<?> request
    ) {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null || caller.isService()) {
            return Flux.just(errorEvent("forbidden", "user identity required"));
        }
        String sub = caller.sub();
        String role = roleOf(caller);
        ConcurrentMap<String, FluxSink<RelayEvent>> pool =
            CLIENT_PC.equals(role) ? pcSinks : phoneSinks;
        markOnline(sub, role, true);
        return Flux.<RelayEvent>create(emitter -> {
            // 同一 sub 同角色只保留最新连接（旧连接被替换即断开）
            FluxSink<RelayEvent> previous = pool.put(sub, emitter);
            if (previous != null) {
                previous.complete();
            }
            emitter.onCancel(() -> disconnect(sub, role, pool, emitter));
            emitter.onDispose(() -> disconnect(sub, role, pool, emitter));
            // 上线补推：缓存中发给本 sub 本角色的未过期消息
            replayCache(sub, role, emitter);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /** 仅接受人类用户身份（PKCE 客户端）；服务身份无对端，403。 */
    private DshIdentity.Principal userOnly(HttpRequest<?> request) {
        DshIdentity.Principal caller = DshIdentity.of(request);
        if (caller == null || caller.isService()) {
            return null;
        }
        return caller;
    }

    /** 由 client_id 判定角色：dsh-pc → PC，dsh-ui → Phone，其余视为未知（不订阅）。 */
    private String roleOf(DshIdentity.Principal caller) {
        return CLIENT_PC.equals(caller.clientId()) ? CLIENT_PC : CLIENT_PHONE;
    }

    private static HttpResponse<Map<String, Object>> forbidden() {
        return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
            .body(Map.of("error", "relay requires a user identity (service identities have no peer)"));
    }

    /**
     * 推送到同 sub 指定角色；目标无连接则 false。PC 角色额外以应用层心跳判活
     * （方案 A'）：SSE 连接在但 lastHeartbeat 超时（PC 半开死机、TCP 半开未被
     * keepalive 判死）→ 视为离线，手机得到确定 offline。
     */
    private boolean deliver(String sub, String role, String type, Map<String, Object> data) {
        FluxSink<RelayEvent> sink = (CLIENT_PC.equals(role) ? pcSinks : phoneSinks).get(sub);
        if (sink == null || sink.isCancelled()) {
            return false;
        }
        if (CLIENT_PC.equals(role) && !pcRecentlyAlive(sub)) {
            return false;
        }
        sink.next(event(type, data));
        return true;
    }

    /** PC 最近一次应用层心跳是否在超时窗口内（90s）。 */
    private boolean pcRecentlyAlive(String sub) {
        Long last = pcHeartbeats.get(sub);
        return last != null && System.currentTimeMillis() - last < PC_HEARTBEAT_TIMEOUT_MS;
    }

    /**
     * 缓存按 (sub, role, type, 载荷指纹) 去重（2026-09-27）：手机端积压指令每 8s
     * 重发一次（flushLocal），此前每发一次生成一个新 UUID 条目（60s TTL 内同指令
     * 最多 ~7 副本）。以内容指纹为 key 覆盖写入：同指令 60s 内只留一条、TTL 随最近
     * 一次重发续期；PC 上线补推语义不变（replayCache 仍按 toSub+role 匹配，推后删除）。
     */
    private void cacheMessage(String sub, String role, String type, Map<String, Object> data) {
        String key = cacheKeyOf(sub, role, type, data);
        cache.put(key, new CachedMessage(sub, role, type, data, System.currentTimeMillis() + CACHE_TTL_MS));
    }

    /**
     * 缓存条目指纹：sub + role + type + 载荷内容。载荷为插入序固定的 LinkedHashMap，
     * 同一条指令（sessionId/text 等全部相同）重发产生同一指纹 → 覆盖去重；
     * 不同内容（新消息/新决策）指纹不同 → 各自独立缓存。
     */
    static String cacheKeyOf(String sub, String role, String type, Map<String, Object> data) {
        return sub + "\u0000" + role + "\u0000" + type + "\u0000" + String.valueOf(data);
    }

    /**
     * Per-sub 滑动窗口宽松限流（2026-09-27 异常兜底）：手机端正常流量
     * ≤~0.26 req/s/sub（列表 20s + 积压重发 8s + 详情轮询 30s），阈值 60 次 / 10s
     * 留 ~23 倍余量——只挡客户端 bug / 重试风暴，不误伤正常使用；纯内存，随
     * purgeCache（30s）清理空桶。SSE 长连接端点（events）不参与限流。
     */
    private boolean rateLimited(String sub) {
        long now = System.currentTimeMillis();
        Deque<Long> window = rateWindows.computeIfAbsent(sub, k -> new ConcurrentLinkedDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() > RATE_LIMIT_WINDOW_MS) {
            window.pollFirst();
        }
        if (window.size() >= RATE_LIMIT_PER_WINDOW) {
            return true;
        }
        window.addLast(now);
        return false;
    }

    private static HttpResponse<Map<String, Object>> tooManyRequests() {
        return HttpResponse.status(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of("error", "rate limit exceeded; retry later"));
    }

    /** 上线补推：把目标离线期间缓存的未过期消息按序推给新连接。 */
    private void replayCache(String sub, String role, FluxSink<RelayEvent> emitter) {
        cache.forEach((msgId, msg) -> {
            if (msg.toSub().equals(sub) && msg.role().equals(role) && !msg.expired()) {
                emitter.next(event(msg.type(), msg.data()));
                cache.remove(msgId);
            }
        });
    }

    private void markOnline(String sub, String role, boolean online) {
        presence.compute(sub, (k, v) -> {
            Map<String, Boolean> m = v == null ? new ConcurrentHashMap<>() : v;
            m.put(role, online);
            return m;
        });
    }

    /**
     * 连接结束清理。条件删除：仅当池中条目仍指向本 sink 才移除并标记离线——
     * PC 重启场景下新连接已 {@code put} 替换旧连接，旧连接的 dispose 不得误删
     * 新连接、也不得把在线状态改回 offline（否则 query 将推给已被删除的 sink）。
     */
    private void disconnect(String sub, String role,
                            ConcurrentMap<String, FluxSink<RelayEvent>> pool,
                            FluxSink<RelayEvent> emitter) {
        if (!pool.remove(sub, emitter)) {
            // 已被同一 sub 的新连接替换——本回调属于旧连接，不触碰新条目。
            return;
        }
        markOnline(sub, role, false);
        if (CLIENT_PC.equals(role)) {
            // 连接没了，PC 活性心跳记录一并清除（新连接会重新上报）。
            pcHeartbeats.remove(sub);
            // 对端感知 PC 离线（pc.status）——仅 PC 断开时通知 Phone 有意义
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("online", false);
            deliver(sub, CLIENT_PHONE, "pc.status", data);
        }
    }

    private void heartbeat() {
        RelayEvent beat = new RelayEvent("heartbeat", UUID.randomUUID().toString(), Map.of());
        pcSinks.forEach((sub, sink) -> safeNext(sink, beat));
        phoneSinks.forEach((sub, sink) -> safeNext(sink, beat));
    }

    private void purgeCache() {
        cache.entrySet().removeIf(e -> e.getValue().expired());
        pendingQueries.entrySet().removeIf(e -> e.getValue().expired());
        queryResults.entrySet().removeIf(e -> e.getValue().expired());
        rateWindows.entrySet().removeIf(e -> e.getValue().isEmpty());
        // 清理已超时 2 倍仍无新心跳的 PC 记录（保持 map 干净；判活本身不受影响）
        pcHeartbeats.entrySet().removeIf(
            e -> System.currentTimeMillis() - e.getValue() > 2 * PC_HEARTBEAT_TIMEOUT_MS
        );
    }

    private static RelayEvent event(String type, Map<String, Object> data) {
        return new RelayEvent(type, UUID.randomUUID().toString(), data);
    }

    private static RelayEvent errorEvent(String type, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", message);
        return new RelayEvent(type, UUID.randomUUID().toString(), data);
    }

    private static void safeNext(FluxSink<RelayEvent> sink,
                                 RelayEvent event) {
        if (!sink.isCancelled()) {
            sink.next(event);
        }
    }
}
