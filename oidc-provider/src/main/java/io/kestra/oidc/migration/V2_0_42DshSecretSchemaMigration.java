package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * dsh managed secrets（OSS 自实现，仿企业版）：创建 {@code dsh_secret} 表。
 *
 * <p>
 * 与 oidc_* 系列迁移同机制（scriptId + classpath SQL 资源，经 Kestra 迁移表幂等执行）。
 * 表承载 (tenant, namespace, secret_key) 隔离的托管 secret，值以 BYTEA 存储官方
 * EncryptionService 的密文（AES-256-GCM，密钥 = kestra.encryption.secret-key）。
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_42DshSecretSchemaMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_42DshSecretSchemaMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.42-dsh-secret-schema";
    }

    @Override
    public String description() {
        return "Create dsh_secret table for managed secrets (namespace-isolated, encrypted)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.42-dsh-secret-schema.sql");
    }
}
