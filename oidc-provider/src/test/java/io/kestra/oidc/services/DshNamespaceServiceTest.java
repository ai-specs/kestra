package io.kestra.oidc.services;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * DshNamespaceService 授权规则单测：
 * ① 链内（祖先）访问维持允许、链外拒绝（原有语义不回归）；
 * ② 云电脑模式窄授权：仅 dsh.exec（全局执行 flow）→ employee.* 单向放行，其余来源
 *    访问 employee.* 仍拒绝（授权点在 DshExecController：白名单 flow + sub==所有者）。
 */
class DshNamespaceServiceTest {

    private final DshNamespaceService service = new DshNamespaceService(null);

    @Test
    void chainSemanticsUnchanged() {
        // 子读祖先允许
        assertThat(service.isAllowedNamespace(null, "a.b", null, "a.b.c"), is(true));
        assertThat(service.isAllowedNamespace(null, "a", null, "a.b.c"), is(true));
        assertThat(service.isAllowedNamespace(null, "a.b.c", null, "a.b.c"), is(true));
        // 链外拒绝（兄弟/无关/子代）
        assertThat(service.isAllowedNamespace(null, "b", null, "a.b.c"), is(false));
        assertThat(service.isAllowedNamespace(null, "a.b.x", null, "a.b.c"), is(false));
    }

    @Test
    void execFlowMayReachEmployeeNamespacesOnly() {
        assertThat(service.isAllowedNamespace(null, "employee.admin-kestra.io-893c1b3a", null, "dsh.exec"), is(true));
        // 其他来源访问 employee.* 仍拒绝
        assertThat(service.isAllowedNamespace(null, "employee.x-1", null, "dsh"), is(false));
        assertThat(service.isAllowedNamespace(null, "employee.x-1", null, "company.team"), is(false));
        assertThat(service.isAllowedNamespace(null, "employee.x-1", null, "employee.y-1"), is(false));
        // dsh.exec 访问非 employee 目标仍按链内规则（company.team 拒绝；dsh/dsh.exec 自身允许）
        assertThat(service.isAllowedNamespace(null, "company.team", null, "dsh.exec"), is(false));
        assertThat(service.isAllowedNamespace(null, "dsh", null, "dsh.exec"), is(true));
    }
}
