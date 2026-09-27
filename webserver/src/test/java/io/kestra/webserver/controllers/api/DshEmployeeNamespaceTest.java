package io.kestra.webserver.controllers.api;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * 云电脑模式员工 namespace 推导（规格 §4.2）的确定性单测：slug 规则、hash 防碰撞、
 * Kestra namespace 形态（^[a-z0-9][a-z0-9._-]*）。
 */
class DshEmployeeNamespaceTest {

    @Test
    void derivesDeterministicNamespace() {
        assertThat(DshEmployeeNamespace.of("admin@kestra.io"),
            equalTo("employee.admin-kestra.io-893c1b3a"));
        // 同一 sub 重复推导结果不变（服务端零状态的前提）
        assertThat(DshEmployeeNamespace.of("admin@kestra.io"),
            equalTo(DshEmployeeNamespace.of("admin@kestra.io")));
    }

    @Test
    void slugFitsKestraNamespaceGrammar() {
        for (String sub : new String[] {"admin@kestra.io", "alice+tag@x.cn", "Bob.Name@Example.COM", "u1", "中文@用户"}) {
            String ns = DshEmployeeNamespace.of(sub);
            assertThat(ns, startsWith("employee."));
            assertThat("namespace charset: " + ns, ns.substring("employee.".length()), matchesPattern("[a-z0-9][a-z0-9._-]*"));
        }
    }

    @Test
    void hashSuffixSeparatesSlugCollisions() {
        // a+b@x 与 a-b@x 的 slug 同形（+ → -），hash8 必须把它们分开
        assertThat(DshEmployeeNamespace.of("a+b@x"), not(equalTo(DshEmployeeNamespace.of("a-b@x"))));
        // 大小写不同的 sub 视为不同用户（hash 按原串计算），slug 一致但 namespace 不同
        assertThat(DshEmployeeNamespace.of("u@x"), not(equalTo(DshEmployeeNamespace.of("U@x"))));
    }

    @Test
    void employeePrefixPredicate() {
        assertThat(DshEmployeeNamespace.isEmployeeNamespace("employee.a-1"), is(true));
        assertThat(DshEmployeeNamespace.isEmployeeNamespace("dsh"), is(false));
        assertThat(DshEmployeeNamespace.isEmployeeNamespace(null), is(false));
    }
}
