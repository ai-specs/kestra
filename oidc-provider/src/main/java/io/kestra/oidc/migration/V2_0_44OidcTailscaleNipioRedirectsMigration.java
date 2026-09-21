package io.kestra.oidc.migration;

import java.util.List;
import javax.sql.DataSource;

import io.kestra.jdbc.migration.AbstractSQLMigrationScript;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Tailscale 跨机访问：为 {@code dsh-ui} / {@code dsh-pc} / {@code nacos} 追加
 * nip.io（及裸 Tailscale IP）回调变体。手机 App/H5 与 PC 均经
 * {@code http://100.71.119.22.nip.io}（DNS 解析到宿主机 Tailscale IP）访问，
 * 授权回跳与 RP-initiated logout 的 {@code redirect_uri} / {@code post_logout_redirect_uri}
 * 以访问时的 origin 动态生成，因此 IdP 侧的注册白名单必须包含这些 origin
 * （开放重定向防护要求与注册 redirect URI 精确匹配）。
 *
 * <p>
 * Merge-style and idempotent like the 2.0.30 / 2.0.38 / 2.0.41 migrations:
 * distinct URIs are unioned, and the statement is a no-op once applied.
 */
@Singleton
@Requires(property = "kestra.repository.type", value = "postgres")
public class V2_0_44OidcTailscaleNipioRedirectsMigration extends AbstractSQLMigrationScript {

    private final DataSource dataSource;

    @Inject
    public V2_0_44OidcTailscaleNipioRedirectsMigration(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return "2.0.44-oidc-tailscale-nipio-redirects";
    }

    @Override
    public String description() {
        return "dsh-ui/dsh-pc/nacos: add Tailscale nip.io and raw-IP redirect URIs";
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.44-oidc-tailscale-nipio-redirects.sql");
    }
}
