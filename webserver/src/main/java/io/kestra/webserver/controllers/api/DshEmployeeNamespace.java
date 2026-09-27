package io.kestra.webserver.controllers.api;

import java.util.zip.CRC32;

/**
 * Employee namespace derivation for the dsh cloud-computer mode
 * (docs/dsh-云电脑模式需求规格.md §4.2): every OIDC sub owns exactly one Kestra
 * namespace {@code employee.{slug}-{hash8}} used as its session-file storage
 * ({@code kestra://{namespace}/sessions/{sessionId}/...}).
 *
 * <p>
 * The derivation is deterministic and stateless — the server recomputes it from the
 * validated token sub on every call and ignores any client-provided namespace, so the
 * mapping needs no server-side store and cannot be forged.
 *
 * <ul>
 *   <li>slug: sub lowercased, every character outside {@code [a-z0-9._-]} replaced by
 *       {@code '-'} (Kestra namespaces must match {@code ^[a-z0-9][a-z0-9._-]*});</li>
 *   <li>hash8: first 8 hex digits of CRC32(sub) — pure slugs can collide
 *       ({@code a+b@x} and {@code a-b@x} normalize alike), the hash suffix cannot.</li>
 * </ul>
 */
public final class DshEmployeeNamespace {

    public static final String PREFIX = "employee.";

    private DshEmployeeNamespace() {}

    /** Deterministic employee namespace for an OIDC sub (e.g. {@code admin@kestra.io} → {@code employee.admin-kestra.io-893c1b3a}). */
    public static String of(String sub) {
        String slug = sub.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        // Kestra namespace 首字符必须 [a-z0-9]：非 ASCII 开头的 sub 会归一成全 '-'（如 中文@用户），
        // 补一个稳定前缀保证形态合法（仍确定无状态）。
        if (slug.isEmpty() || !(slug.charAt(0) >= 'a' && slug.charAt(0) <= 'z')
            && !(slug.charAt(0) >= '0' && slug.charAt(0) <= '9')) {
            slug = "u" + slug;
        }
        CRC32 crc = new CRC32();
        crc.update(sub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash8 = String.format("%08x", crc.getValue());
        return PREFIX + slug + "-" + hash8;
    }

    /** True when the namespace is inside the employee namespace space (the shared exec flow's grant target, see DshNamespaceService). */
    public static boolean isEmployeeNamespace(String namespace) {
        return namespace != null && namespace.startsWith(PREFIX);
    }
}
