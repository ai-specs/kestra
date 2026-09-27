package io.kestra.relay.controllers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;

import io.kestra.relay.DshIdentity;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import reactor.core.publisher.FluxSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DshRelayController 纯单元测试（Mockito 直接驱动 controller 实例，不依赖 OIDC 装配）：
 * 覆盖 per-sub 宽松限流、缓存去重、PC 离线语义。半开判死由 TCP keepalive
 * （RelayKeepAliveCustomizer）在连接真空闲时承担——本进程不发出站 SSE ping。
 */
class DshRelayControllerTest {

    private static HttpRequest<?> requestFor(String sub, String clientId) {
        @SuppressWarnings("unchecked")
        HttpRequest<?> request = mock(HttpRequest.class);
        when(request.getAttribute(DshIdentity.CLAIMS_ATTRIBUTE, Map.class))
            .thenReturn(Optional.of(Map.of("sub", sub, "client_id", clientId, "roles", List.of())));
        return request;
    }

    @Test
    void rateLimitBlocksRunawayClientPerSub() {
        DshRelayController controller = new DshRelayController();
        HttpRequest<?> request = requestFor("alice@kestra.io", "dsh-ui");
        var body = new DshRelayController.RelayInput("hello", UUID.randomUUID().toString(), true, null);
        for (int i = 0; i < 60; i++) {
            HttpResponse<Map<String, Object>> response = controller.input(request, body);
            assertEquals(HttpStatus.OK, response.getStatus(), "前 60 次在窗口内应放行");
            assertEquals(false, response.getBody().orElseThrow().get("delivered"));
        }
        HttpResponse<Map<String, Object>> blocked = controller.input(request, body);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getStatus(), "窗口内第 61 次应被限流");
        assertTrue(String.valueOf(blocked.getBody().orElseThrow().get("error")).contains("rate limit"));
    }

    @Test
    void rateLimitIsPerSubNotGlobal() {
        DshRelayController controller = new DshRelayController();
        var body = new DshRelayController.RelayInput("hello", UUID.randomUUID().toString(), true, null);
        for (int i = 0; i < 60; i++) {
            controller.input(requestFor("alice@kestra.io", "dsh-ui"), body);
        }
        // 另一个 sub 不受 alice 的窗口影响
        HttpResponse<Map<String, Object>> other = controller.input(requestFor("bob@kestra.io", "dsh-ui"), body);
        assertEquals(HttpStatus.OK, other.getStatus());
    }

    @Test
    void cacheKeyDedupsSameInstructionAndKeepsDifferentOnes() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("text", "继续");
        first.put("sessionId", "s-1");
        first.put("newSession", Boolean.FALSE);

        Map<String, Object> identical = new LinkedHashMap<>();
        identical.put("text", "继续");
        identical.put("sessionId", "s-1");
        identical.put("newSession", Boolean.FALSE);

        Map<String, Object> differentText = new LinkedHashMap<>();
        differentText.put("text", "换个问题");
        differentText.put("sessionId", "s-1");
        differentText.put("newSession", Boolean.FALSE);

        assertEquals(
            DshRelayController.cacheKeyOf("alice", "dsh-pc", "session.input", first),
            DshRelayController.cacheKeyOf("alice", "dsh-pc", "session.input", identical),
            "同一条指令（同 session 同 text）重发必须去重为同一缓存条目"
        );
        assertNotEquals(
            DshRelayController.cacheKeyOf("alice", "dsh-pc", "session.input", first),
            DshRelayController.cacheKeyOf("alice", "dsh-pc", "session.input", differentText),
            "不同内容的指令必须各自独立缓存"
        );
        assertNotEquals(
            DshRelayController.cacheKeyOf("alice", "dsh-pc", "session.input", first),
            DshRelayController.cacheKeyOf("bob", "dsh-pc", "session.input", first),
            "不同 sub 的缓存必须隔离"
        );
    }

    @Test
    void queryReturnsOfflineWhenPcDisconnected() {
        DshRelayController controller = new DshRelayController();
        HttpRequest<?> request = requestFor("alice@kestra.io", "dsh-ui");
        var body = new DshRelayController.RelayQuery("rid-1", "session.list", null, null);
        HttpResponse<Map<String, Object>> response = controller.query(request, body);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(Boolean.TRUE, response.getBody().orElseThrow().get("offline"));
    }

    @Test
    void serviceIdentityIsRejected() {
        DshRelayController controller = new DshRelayController();
        // client_credentials 服务身份：sub == client_id → 一律 403（无人类对端）
        HttpRequest<?> request = requestFor("dsh", "dsh");
        var body = new DshRelayController.RelayInput("hello", UUID.randomUUID().toString(), true, null);
        HttpResponse<Map<String, Object>> response = controller.input(request, body);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatus());
    }

    // ── SSE 连接即在线（半开判死由 TCP keepalive 承担）─────────────────────────

    /** SSE 连接建立：往 pcSinks 放一个未取消的 sink（等价 PC 在线）。 */
    private static FluxSink<DshRelayController.RelayEvent> attachPcSse(DshRelayController controller, String sub) {
        @SuppressWarnings("unchecked")
        FluxSink<DshRelayController.RelayEvent> sink = mock(FluxSink.class);
        when(sink.isCancelled()).thenReturn(false);
        attachViaReflection(controller, sub, sink);
        return sink;
    }

    private static void attachViaReflection(DshRelayController controller, String sub, FluxSink<DshRelayController.RelayEvent> sink) {
        try {
            var field = DshRelayController.class.getDeclaredField("pcSinks");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentMap<String, FluxSink<DshRelayController.RelayEvent>> pcSinks =
                (ConcurrentMap<String, FluxSink<DshRelayController.RelayEvent>>) field.get(controller);
            pcSinks.put(sub, sink);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void inputDeliversWhenPcSseConnected() {
        DshRelayController controller = new DshRelayController();
        String sub = "alice@kestra.io";
        attachPcSse(controller, sub);
        var body = new DshRelayController.RelayInput("hello", UUID.randomUUID().toString(), true, null);
        HttpResponse<Map<String, Object>> input = controller.input(requestFor(sub, "dsh-ui"), body);
        assertEquals(HttpStatus.OK, input.getStatus());
        assertEquals(Boolean.TRUE, input.getBody().orElseThrow().get("delivered"));
    }

    @Test
    void inputCachesWhenPcSseAbsent() {
        DshRelayController controller = new DshRelayController();
        String sub = "alice@kestra.io";
        // 不 attach SSE → PC 离线 → delivered:false + cached:true
        var body = new DshRelayController.RelayInput("hello", UUID.randomUUID().toString(), true, null);
        HttpResponse<Map<String, Object>> input = controller.input(requestFor(sub, "dsh-ui"), body);
        assertEquals(Boolean.FALSE, input.getBody().orElseThrow().get("delivered"));
        assertEquals(Boolean.TRUE, input.getBody().orElseThrow().get("cached"));
    }

}
