package io.kestra.oidc.services;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import io.kestra.core.encryption.EncryptionConfig;
import io.kestra.core.encryption.EncryptionService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL-backed managed secret store for the dsh overlay (仿企业版能力)。
 *
 * <p>
 * 前提：应用已配置系统加密密钥 {@code kestra.encryption.secret-key}
 * （环境变量 {@code KESTRA_ENCRYPTION_SECRET_KEY}）——未配置时本 bean 不注册，
 * 整个 DB 管理能力（{@link DshSecretService} 替换、{@code DshSecretAdminController}）
 * 随 {@code @Requires(bean=...)} 链整体停用，环境变量 secret 模式原样可用。
 *
 * <p>
 * 存储：{@code dsh_secret} 表，值经 {@link EncryptionService}（AES/GCM/NoPadding，
 * 随机 IV 前置）加密后落库，运行时解密。
 *
 * <p>
 * 隔离语义（用户确认）：读取按 namespace 继承链（本 namespace → 逐级祖先），
 * 链外（兄弟/无关 namespace）不可见；环境变量 secret 无 namespace 属性，保持全局兜底。
 */
@Singleton
@Slf4j
@Requires(property = "kestra.repository.type", value = "postgres")
@Requires(property = "kestra.encryption.secret-key")
public class PostgresSecretStore {

    /** 根 namespace 的存储表示（Kestra 层级 namespace 无强制根名，链止于最顶层一段）。 */
    private static final String ROOT_NS = ".";

    private final DataSource dataSource;
    private final String encryptionKey;

    @Inject
    public PostgresSecretStore(
        DataSource dataSource,
        EncryptionConfig encryptionConfig,
        @Value("${kestra.encryption.secret-key}") String encryptionKey
    ) {
        // 与 OidcUserService 相同：unwrap Micronaut Data AOP 代理，使 getConnection() 可在
        // 非 @Connectable 上下文工作。
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.encryptionKey = encryptionKey;
    }

    /** 一条已解密的托管 secret（值仅在本进程内存中存在）。 */
    /** 一条已解密的托管 secret（值仅在本进程内存中存在；description 为唯一元数据）。 */
    public record SecretRecord(String namespace, String key, String value, String description) {}

    /** 托管 secret 的列表元数据（无值）——managed 端点展示用。 */
    public record SecretMeta(String namespace, String key, String description) {}

    /**
     * 按 namespace 继承链查找并解密 secret；本 namespace 优先，其次逐级祖先。
     *
     * @return 命中返回 {@link SecretRecord}；链上均未命中返回 {@link Optional#empty()}
     */
    public Optional<SecretRecord> find(String tenantId, String namespace, String key) throws IOException {
        for (String ns : namespaceChain(namespace)) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement("""
                     SELECT namespace, secret_key, secret_value, description
                     FROM dsh_secret
                     WHERE tenant_id = ? AND namespace = ? AND secret_key = ?
                     """)) {
                ps.setString(1, tenantId);
                ps.setString(2, ns);
                ps.setString(3, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] cipher = rs.getBytes("secret_value");
                        String plain = new String(EncryptionService.decrypt(encryptionKey, cipher));
                        return Optional.of(new SecretRecord(
                            rs.getString("namespace"),
                            rs.getString("secret_key"),
                            plain,
                            rs.getString("description")));
                    }
                }
            } catch (SQLException | GeneralSecurityException e) {
                throw new IOException("Failed to read managed secret '" + key + "' from namespace '" + ns + "'", e);
            }
        }
        return Optional.empty();
    }

    /** 该 namespace 自己的托管 secret 元数据（不含继承，不含值）。 */
    public List<SecretRecord> listOwn(String tenantId, String namespace) throws IOException {
        List<SecretRecord> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 SELECT namespace, secret_key, description
                 FROM dsh_secret
                 WHERE tenant_id = ? AND namespace = ?
                 ORDER BY secret_key
                 """)) {
            ps.setString(1, tenantId);
            ps.setString(2, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SecretRecord(
                        rs.getString("namespace"),
                        rs.getString("secret_key"),
                        null,
                        rs.getString("description")));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list managed secrets for namespace '" + namespace + "'", e);
        }
        return out;
    }

    /** 继承链上各级 namespace 各自的 secret key 集合（不含值）——对齐 inherited-secrets 端点结构。 */
    public Map<String, Set<String>> inheritedKeys(String tenantId, String namespace) throws IOException {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        for (String ns : namespaceChain(namespace)) {
            List<SecretRecord> rows = listOwn(tenantId, ns);
            if (!rows.isEmpty()) {
                Set<String> keys = new LinkedHashSet<>();
                for (SecretRecord row : rows) {
                    keys.add(row.key());
                }
                out.put(ns, keys);
            }
        }
        return out;
    }

    /** 全库托管 secret 的 (namespace → key 集合) 映射（UI 行级只读判定用，须精确到 namespace+key）。 */
    public Map<String, Set<String>> allManagedKeys(String tenantId) throws IOException {
        Map<String, Set<String>> byNamespace = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 SELECT namespace, secret_key FROM dsh_secret WHERE tenant_id = ?
                 """)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ns = rs.getString("namespace");
                    byNamespace.computeIfAbsent(ns, k -> new LinkedHashSet<>())
                        .add(rs.getString("secret_key"));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list managed secret keys", e);
        }
        return byNamespace;
    }

    /** 全库托管 secret 的列表元数据（namespace/key/description，无值）——managed 端点展示用。 */
    public List<SecretMeta> allManagedMetadata(String tenantId) throws IOException {
        List<SecretMeta> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 SELECT namespace, secret_key, description FROM dsh_secret WHERE tenant_id = ?
                 ORDER BY namespace, secret_key
                 """)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SecretMeta(
                        rs.getString("namespace"),
                        rs.getString("secret_key"),
                        rs.getString("description")));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list managed secret metadata", e);
        }
        return out;
    }

    public boolean exists(String tenantId, String namespace, String key) throws IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 SELECT 1 FROM dsh_secret WHERE tenant_id = ? AND namespace = ? AND secret_key = ?
                 """)) {
            ps.setString(1, tenantId);
            ps.setString(2, namespace);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IOException("Failed to check managed secret '" + key + "'", e);
        }
    }

    /** 创建（值必填）。 */
    public void put(String tenantId, String namespace, String key, String value, String description)
        throws IOException {
        byte[] cipher = encrypt(value);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 INSERT INTO dsh_secret (tenant_id, namespace, secret_key, secret_value, description)
                 VALUES (?, ?, ?, ?, ?)
                 """)) {
            ps.setString(1, tenantId);
            ps.setString(2, namespace);
            ps.setString(3, key);
            ps.setBytes(4, cipher);
            ps.setString(5, description);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to create managed secret '" + key + "'", e);
        }
    }

    /** 更新（value 为 null 或空白表示仅更新元数据，防止空值覆盖真实秘密）。 */
    public void update(String tenantId, String namespace, String key, String value, String description)
        throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StringBuilder sql = new StringBuilder("UPDATE dsh_secret SET updated_at = now()");
                List<Object> args = new ArrayList<>();
                if (value != null && !value.isBlank()) {
                    sql.append(", secret_value = ?");
                    args.add(encrypt(value));
                }
                if (description != null) {
                    sql.append(", description = ?");
                    args.add(description);
                }
                sql.append(" WHERE tenant_id = ? AND namespace = ? AND secret_key = ?");
                try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                    int idx = 1;
                    for (Object arg : args) {
                        if (arg instanceof byte[] bytes) {
                            ps.setBytes(idx, bytes);
                        } else {
                            ps.setString(idx, (String) arg);
                        }
                        idx++;
                    }
                    ps.setString(idx++, tenantId);
                    ps.setString(idx++, namespace);
                    ps.setString(idx, key);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to update managed secret '" + key + "'", e);
        }
    }

    public void delete(String tenantId, String namespace, String key) throws IOException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                 DELETE FROM dsh_secret WHERE tenant_id = ? AND namespace = ? AND secret_key = ?
                 """)) {
            ps.setString(1, tenantId);
            ps.setString(2, namespace);
            ps.setString(3, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to delete managed secret '" + key + "'", e);
        }
    }

    /** 继承链：a.b.c → [a.b.c, a.b, a]；a → [a]；根/空 → [.]。 */
    public static List<String> namespaceChain(String namespace) {
        if (namespace == null || namespace.isBlank() || namespace.equals(ROOT_NS)) {
            return List.of(ROOT_NS);
        }
        String[] parts = namespace.split("\\.");
        List<String> chain = new ArrayList<>(parts.length);
        for (int i = parts.length; i >= 1; i--) {
            chain.add(String.join(".", java.util.Arrays.copyOf(parts, i)));
        }
        return chain;
    }

    private byte[] encrypt(String plain) throws IOException {
        try {
            return EncryptionService.encrypt(encryptionKey, plain.getBytes());
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to encrypt secret", e);
        }
    }
}
