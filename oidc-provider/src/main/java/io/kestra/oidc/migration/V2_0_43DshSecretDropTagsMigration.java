package io.kestra.oidc.migration;

import java.util.List;

import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * dsh managed secrets：移除 {@code dsh_secret.tags} 列（用户确认标签不参与任何
 * 选取/隔离逻辑，且上游企业版是否含 tags 无法确认，予以删除；description 保留）。
 *
 * <p>
 * 幂等（{@code DROP COLUMN IF EXISTS}）：新建库（2.0.42 建表已无 tags 列）执行无害。
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_43DshSecretDropTagsMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_43DshSecretDropTagsMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.43-dsh-secret-drop-tags";
    }

    @Override
    public String description() {
        return "Drop dsh_secret.tags column (tags removed as unused metadata)";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.43-dsh-secret-drop-tags.sql");
    }
}
