package io.kestra.webserver.controllers.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.micronaut.http.exceptions.HttpStatusException;
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

    @Test
    void rejectTraversal() {
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/../secret.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/../../secret.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/%2e%2e/x.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("notapps/hello/x.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps"));
    }

    @Test
    void rejectReservedDesigner() {
        HttpStatusException e = assertThrows(HttpStatusException.class,
            () -> AppsFileController.validateConventionPath("apps/designer/index.json"));
        assertEquals(400, e.getStatus().getCode());
    }

    @Test
    void rejectBadNamesAndNonJson() {
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/ümlaut.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/a b.json"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/readme.md"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath("apps/hello/"));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath(null));
        assertThrows(HttpStatusException.class, () -> AppsFileController.validateConventionPath(""));
    }
}
