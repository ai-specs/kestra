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

    // ── buildPagesFromRelative ─────────────────────────────────────────────

    @Test
    void simplePagesWithIndexFirst() {
        List<Map<String, Object>> pages = AppsFileController.buildPagesFromRelative(List.of(
            "hello/other.json",
            "hello/index.json"
        ));
        assertEquals(2, pages.size());
        assertEquals("index", pages.get(0).get("name"));
        assertEquals(Boolean.TRUE, pages.get(0).get("index"));
        assertEquals("page", pages.get(0).get("kind"));
        assertEquals("other", pages.get(1).get("name"));
    }

    @Test
    void groupWithChildren() {
        List<Map<String, Object>> pages = AppsFileController.buildPagesFromRelative(List.of(
            "hello/reports/",
            "hello/reports/monthly.json",
            "hello/reports/daily.json"
        ));
        assertEquals(1, pages.size());
        Map<String, Object> group = pages.get(0);
        assertEquals("reports", group.get("name"));
        assertEquals("group", group.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) group.get("children");
        assertEquals(List.of("daily", "monthly"), children.stream().map(c -> c.get("name")).toList());
    }

    @Test
    void sameNameFileAndDirMergeIntoOneNode() {
        // 同名并存：x.json（自身页）+ x/（分组）→ 一个节点，kind=page，children 非空
        List<Map<String, Object>> pages = AppsFileController.buildPagesFromRelative(List.of(
            "hello/x.json",
            "hello/x/",
            "hello/x/sub.json"
        ));
        assertEquals(1, pages.size());
        Map<String, Object> node = pages.get(0);
        assertEquals("x", node.get("name"));
        assertEquals("page", node.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        assertEquals(1, children.size());
        assertEquals("sub", children.get(0).get("name"));
    }

    @Test
    void deeperThanThreeLevelsIgnored() {
        List<Map<String, Object>> pages = AppsFileController.buildPagesFromRelative(List.of(
            "hello/a/b/c.json" // 四级，超出约定（三级封顶）
        ));
        assertTrue(pages.isEmpty());
    }

    @Test
    void nonJsonFilesIgnored() {
        List<Map<String, Object>> pages = AppsFileController.buildPagesFromRelative(List.of(
            "hello/readme.md",
            "hello/assets/logo.png"
        ));
        assertTrue(pages.isEmpty());
    }
}
