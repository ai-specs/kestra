package io.kestra.webserver.controllers.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppsFileControllerTest {

    // ── validateConventionPath ─────────────────────────────────────────────

    @Test
    void validPaths() {
        assertEquals(Path.of("apps/hello/index.json"), AppsFileController.validateConventionPath("apps/hello/index.json"));
        assertEquals(Path.of("apps/hello/form.json"), AppsFileController.validateConventionPath("apps/hello/form.json"));
        assertEquals(Path.of("apps/hello/x/y.json"), AppsFileController.validateConventionPath("apps/hello/x/y.json"));
        // 前导斜杠与深层路径
        assertEquals(Path.of("apps/hello/a/b/c.json"), AppsFileController.validateConventionPath("/apps/hello/a/b/c.json"));
        // 下划线/连字符/数字
        assertEquals(Path.of("apps/my_app-2/Page_1.json"), AppsFileController.validateConventionPath("apps/my_app-2/Page_1.json"));
    }

    // 防探测语义（2026-09 起）：非法/越界/保留名一律抛 NotFoundResponseException——
    // 空 body 404，不泄露拒绝原因（区别于上游 HttpStatusException 的带 detail 400/404）。
    @Test
    void rejectTraversal() {
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/../secret.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/../../secret.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/%2e%2e/x.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("notapps/hello/x.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps"));
    }

    @Test
    void rejectReservedDesigner() {
        assertThrows(NotFoundResponseException.class,
            () -> AppsFileController.validateConventionPath("apps/designer/index.json"));
    }

    @Test
    void rejectBadNamesAndNonJson() {
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/ümlaut.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/a b.json"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/readme.md"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath("apps/hello/"));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath(null));
        assertThrows(NotFoundResponseException.class, () -> AppsFileController.validateConventionPath(""));
    }
}
