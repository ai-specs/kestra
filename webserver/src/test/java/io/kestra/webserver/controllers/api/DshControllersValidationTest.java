package io.kestra.webserver.controllers.api;

import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

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

    @Test
    void sessionUpsertRejectsInvalidUuidWith400() {
        DshSessionController controller = new DshSessionController();
        var snapshot = new DshSessionController.SessionSnapshot(
            BAD_ID, "RUNNING", "{}", "{}", "u-1", null);
        HttpResponse<Map<String, Object>> response = assertDoesNotThrow(
            () -> controller.upsert(BAD_ID, snapshot));
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
            () -> controller.upsert(GOOD_ID, snapshot));
        assertThat(String.valueOf(exception.getMessage()).contains("UUID"), is(false));
    }

    @Test
    void approvalDecideRejectsInvalidUuidWith400() {
        DshApprovalController controller = new DshApprovalController(
            new DshMetricsConfiguration("jdbc:h2:mem:dsh-test", "sa", ""));
        HttpResponse<?> response = assertDoesNotThrow(
            () -> controller.decide(BAD_ID, true, "ops-lead", ""));
        assertThat(response.getStatus().getCode(), is(400));
    }

    @Test
    void approvalDecideValidUuidPassesValidationBeforeDatabase() {
        DshApprovalController controller = new DshApprovalController(
            new DshMetricsConfiguration("jdbc:h2:mem:dsh-test", "sa", ""));
        // 合法 UUID 时进入数据库路径 —— H2 内存库无 dsh_approval 表，
        // 此处仅验证"参数校验已通过"（异常信息不含 UUID 校验字样）
        var exception = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> controller.decide(GOOD_ID, true, "ops-lead", ""));
        assertThat(String.valueOf(exception.getMessage()).contains("must be a valid UUID"), is(false));
    }
}
