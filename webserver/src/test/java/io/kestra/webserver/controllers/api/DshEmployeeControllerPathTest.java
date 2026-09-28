package io.kestra.webserver.controllers.api;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 员工文件浏览面（/file、/file/download）的路径边界单测：normalizePath 的归一与
 * 拒绝矩阵 + storageUri 的员工命名空间边界断言（可达范围钉死在 /employee/**\/_files/
 * 内）——「不能跨目录任意下载任何文件」的回归锁。
 */
class DshEmployeeControllerPathTest {

    // ── normalizePath：合法路径保持 ────────────────────────────────────────────

    @Test
    void keepsPlainPaths() {
        assertThat(DshEmployeeController.normalizePath("/sessions/abc/reply.txt"),
            equalTo("/sessions/abc/reply.txt"));
        // 无前导斜杠补齐
        assertThat(DshEmployeeController.normalizePath("sessions/a.txt"),
            equalTo("/sessions/a.txt"));
        // 重复斜杠折叠
        assertThat(DshEmployeeController.normalizePath("//sessions///a.txt"),
            equalTo("/sessions/a.txt"));
        // .. 出现在合法文件名内部不误伤（按段判）
        assertThat(DshEmployeeController.normalizePath("/sessions/legit..name.txt"),
            equalTo("/sessions/legit..name.txt"));
        assertThat(DshEmployeeController.normalizePath("/a...b/c.txt"),
            equalTo("/a...b/c.txt"));
        // 点文件（隐藏文件）合法——排障面要求显示
        assertThat(DshEmployeeController.normalizePath("/sessions/.hidden"),
            equalTo("/sessions/.hidden"));
        // 空格/井号/百分号等 URI 特殊字符在文件名里合法（多参 URI 构造器负责编码）
        assertThat(DshEmployeeController.normalizePath("/weird name!#%@.txt"),
            equalTo("/weird name!#%@.txt"));
    }

    // ── normalizePath：穿越与畸形拒绝 ─────────────────────────────────────────

    @Test
    void rejectsTraversalSegments() {
        // 经典穿越
        assertThat(DshEmployeeController.normalizePath("/../etc/passwd"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/../../etc/passwd"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/sessions/../../other-ns/_files/x"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/sessions/..//..//x"), nullValue());
        // 中段穿越
        assertThat(DshEmployeeController.normalizePath("/a/../b"), nullValue());
        // 尾段穿越
        assertThat(DshEmployeeController.normalizePath("/a/b/.."), nullValue());
        // 裸 ..
        assertThat(DshEmployeeController.normalizePath(".."), nullValue());
        assertThat(DshEmployeeController.normalizePath("../x"), nullValue());
        // 单点段（无意义且属畸形）
        assertThat(DshEmployeeController.normalizePath("/./x"), nullValue());
    }

    @Test
    void rejectsBackslashAndControlChars() {
        // Windows 风格穿越（存储层会转 \ 为 /，这里提前拒绝）
        assertThat(DshEmployeeController.normalizePath("..\\..\\etc\\passwd"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/a\\b.txt"), nullValue());
        // ISO 控制字符
        assertThat(DshEmployeeController.normalizePath("/a\u0000b"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/a\nb"), nullValue());
        assertThat(DshEmployeeController.normalizePath("/a\tb"), nullValue());
        // 空路径
        assertThat(DshEmployeeController.normalizePath(null), nullValue());
        assertThat(DshEmployeeController.normalizePath(""), nullValue());
        assertThat(DshEmployeeController.normalizePath("  "), nullValue());
    }

    // ── storageUri：员工命名空间边界断言 ──────────────────────────────────────

    @Test
    void storageUriStaysInsideEmployeeNamespace() {
        URI uri = DshEmployeeController.storageUri("employee.admin-kestra.io-893c1b3a", "sessions/x/reply.txt");
        // 可达范围 = 该员工命名空间的 _files 子树
        assertThat(uri.getPath(), startsWith("/employee/admin-kestra/io-893c1b3a/_files/"));
        // URI 特殊字符被百分号编码进 path（而非截断/当 fragment）
        URI weird = DshEmployeeController.storageUri("employee.x.y", "a b#c%d.txt");
        assertThat(weird.getPath(), equalTo("/employee/x/y/_files/a b#c%d.txt"));
    }

    @Test
    void storageUriThrowsOnEscapeAttempts() {
        // .. 段（防御 normalizePath 被未来重构绕过的兜底）
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("employee.x", "../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("employee.x", "a/../../b"));
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("employee.x", "a/b/.."));
        // 反斜杠
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("employee.x", "..\\..\\x"));
        // 非 employee 前缀的 namespace（守卫被绕过时的最后一道）
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("dsh.exec", "flows/x.yml"));
        assertThrows(IllegalArgumentException.class,
            () -> DshEmployeeController.storageUri("tutorial", "x.txt"));
    }
}
