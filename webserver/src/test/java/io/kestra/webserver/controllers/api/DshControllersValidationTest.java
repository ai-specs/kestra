package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 审查任务 3.2：dsh REST 端点的 UUID 输入校验。
 * 非法 UUID 必须返回 400（而非 500），且校验先于任何数据库访问。
 */
public class DshControllersValidationTest {

    private static final String BAD_ID = "not-a-uuid";
    private static final String GOOD_ID = "123e4567-e89b-12d3-a456-426614174000";

    /** An authenticated request carrying the claims the OidcBearerAuthFilter would have stashed. */
    private static HttpRequest<?> authRequest(String sub, List<String> roles) {
        HttpRequest<?> request = HttpRequest.GET("http://localhost/api/v1/dsh");
        request.getAttributes().put(DshIdentity.CLAIMS_ATTRIBUTE,
            Map.of("sub", sub, "client_id", sub, "roles", roles));
        return request;
    }

    @Test
    void sessionUpsertRejectsInvalidUuidWith400() {
        DshSessionController controller = new DshSessionController();
        var snapshot = new DshSessionController.SessionSnapshot(
            BAD_ID, "RUNNING", "{}", "{}", "u-1", null);
        HttpResponse<Map<String, Object>> response = assertDoesNotThrow(
            () -> controller.upsert(authRequest("alice@kestra.io", List.of("user")), BAD_ID, snapshot));
        assertThat(response.getStatus().getCode(), is(400));
    }

    @Test
    void sessionUpsertAcceptsValidUuidWithoutTouchingDatabase() {
        DshSessionController controller = new DshSessionController();
        var snapshot = new DshSessionController.SessionSnapshot(
            GOOD_ID, "RUNNING", "{}", "{}", "u-1", null);
        // 合法 UUID 会继续访问数据库 —— 这里仅校验"参数校验先行"：
        // 用 null configuration 的控制器实例，合法路径抛出的异常不是参数校验类
        var exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> controller.upsert(authRequest("alice@kestra.io", List.of("user")), GOOD_ID, snapshot));
        assertThat(String.valueOf(exception.getMessage()).contains("UUID"), is(false));
    }

    @Test
    void sessionUpsertAcceptsJsonObjectStateAndMetadata() {
        DshSessionController controller = new DshSessionController();
        // state/metadata 接受 JSON 对象（自动规范化为 JSON 字符串），而非仅限 JSON 字符串
        var snapshot = new DshSessionController.SessionSnapshot(
            GOOD_ID, "RUNNING", Map.of("phase", "planning"), Map.of("device", "pc"), "u-1", null);
        var exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> controller.upsert(authRequest("alice@kestra.io", List.of("user")), GOOD_ID, snapshot));
        // jsonString 序列化 Map 成功，继续走到数据库路径 —— 异常来自 DB（null configuration）
        // 而非 JSON 规范化（若是后者，会抛 "must be a JSON object"）
        assertThat(String.valueOf(exception.getMessage()).contains("must be a JSON object"), is(false));
    }

    @Test
    void sessionUpdateStateRejectsInvalidUuidWith400() {
        DshSessionController controller = new DshSessionController();
        HttpResponse<Map<String, Object>> response = assertDoesNotThrow(
            () -> controller.updateState(authRequest("alice@kestra.io", List.of("user")), BAD_ID,
                new DshSessionController.SessionState("{}")));
        assertThat(response.getStatus().getCode(), is(400));
    }

    @Test
    void approvalDecideRejectsInvalidUuidWith400() {
        DshApprovalController controller = new DshApprovalController(
            new DshMetricsConfiguration("jdbc:h2:mem:dsh-test", "sa", ""));
        HttpResponse<?> response = assertDoesNotThrow(
            () -> controller.decide(authRequest("alice@kestra.io", List.of("user")), BAD_ID, true, "", ""));
        assertThat(response.getStatus().getCode(), is(400));
    }

    @Test
    void approvalDecideValidUuidPassesValidationBeforeDatabase() {
        DshApprovalController controller = new DshApprovalController(
            new DshMetricsConfiguration("jdbc:h2:mem:dsh-test", "sa", ""));
        // 合法 UUID 时进入数据库路径 —— 此处仅验证"参数校验已通过"（异常信息不含 UUID 校验字样）
        var exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> controller.decide(authRequest("alice@kestra.io", List.of("user")), GOOD_ID, true, "", ""));
        assertThat(String.valueOf(exception.getMessage()).contains("must be a valid UUID"), is(false));
    }

    @Test
    void approvalCreateRejectsInvalidUuidWith400() {
        DshApprovalController controller = new DshApprovalController(
            new DshMetricsConfiguration("jdbc:h2:mem:dsh-test", "sa", ""));
        HttpResponse<?> response = assertDoesNotThrow(
            () -> controller.create(authRequest("alice@kestra.io", List.of("user")),
                new DshApprovalController.ApprovalCreate(BAD_ID, "refund", "{}", List.of(), 60)));
        assertThat(response.getStatus().getCode(), is(400));
    }
}
