package io.kestra.oidc.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.kestra.core.models.QueryFilter;
import io.kestra.core.repositories.ArrayListTotal;
import io.kestra.core.secret.SecretNotFoundException;
import io.kestra.core.secret.SecretService;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.data.model.Pageable;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * 叠加版 SecretService（dsh OSS 自实现，仿企业版能力）。
 *
 * <p>
 * 以 Micronaut {@code @Replaces} 无侵入替换 {@link SecretService}（core 开源代码零修改）：
 * flow 运行时 {@code {{ secret('K') }}}（SecretFunction 经 {@code Provider<SecretService>}）
 * 与读列表（SecretController / NamespaceSecretController 注入的 {@code SecretService<String>}）
 * 自动使用本实例。
 *
 * <p>
 * 读取语义（用户确认的隔离模型）：
 * <ol>
 *   <li>托管 secret：按 namespace 继承链查找（本 namespace → 逐级祖先），链外不可见；</li>
 *   <li>环境变量 secret（{@code SECRET_*}，Base64）：无 namespace 属性，保持全局兜底。</li>
 * </ol>
 *
 * <p>
 * 仅在 {@link PostgresSecretStore} 注册（即系统加密密钥已配置）时生效；否则本 bean 不注册，
 * 原 {@link SecretService} 原样工作（纯环境变量模式）。
 */
@Singleton
@Slf4j
@Replaces(SecretService.class)
@Requires(bean = PostgresSecretStore.class)
public class DshSecretService extends SecretService<String> {

    private static final String SECRET_PREFIX = "SECRET_";

    private final PostgresSecretStore store;

    /** 环境变量注入的 secret（Base64 解码后），本类自维护——不依赖父类 private 字段。 */
    private Map<String, String> envSecrets;

    @Inject
    public DshSecretService(PostgresSecretStore store) {
        this.store = store;
    }

    @PostConstruct
    void init() {
        this.envSecrets = decodeEnvSecrets();
    }

    /** 与父类相同的环境变量解析：SECRET_ 前缀 + Base64 值，key 大写化。 */
    static Map<String, String> decodeEnvSecrets() {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (!entry.getKey().startsWith(SECRET_PREFIX)) {
                continue;
            }
            try {
                String value = entry.getValue().replaceAll("\\R", "");
                secrets.put(
                    entry.getKey().substring(SECRET_PREFIX.length()).toUpperCase(),
                    new String(Base64.getDecoder().decode(value)));
            } catch (IllegalArgumentException e) {
                log.error("Could not decode secret '{}', make sure it is Base64-encoded: {}", entry.getKey(), e.getMessage());
            }
        }
        return secrets;
    }

    @Override
    public String findSecret(String tenantId, String namespace, String key) throws SecretNotFoundException, IOException {
        String normalizedKey = key.toUpperCase();
        Optional<PostgresSecretStore.SecretRecord> managed = store.find(tenantId, namespace, normalizedKey);
        if (managed.isPresent()) {
            return managed.get().value();
        }
        String env = envSecrets.get(normalizedKey);
        if (env == null) {
            throw new SecretNotFoundException("Cannot find secret for key '" + key + "'.");
        }
        return env;
    }

    @Override
    public ArrayListTotal<String> list(Pageable pageable, String tenantId, List<QueryFilter> filters) throws IOException {
        final Predicate<String> queryPredicate = filters.stream()
            .filter(filter -> QueryFilter.Field.QUERY.equals(filter.field()) && filter.value() != null)
            .findFirst()
            .map(filter ->
            {
                if (QueryFilter.Op.EQUALS.equals(filter.operation())) {
                    return (Predicate<String>) s -> org.apache.commons.lang3.Strings.CI.contains(s, (String) filter.value());
                } else if (QueryFilter.Op.NOT_EQUALS.equals(filter.operation())) {
                    return (Predicate<String>) s -> !org.apache.commons.lang3.Strings.CI.contains(s, (String) filter.value());
                } else {
                    throw new IllegalArgumentException("Unsupported operation for QUERY filter: " + filter.operation());
                }
            })
            .orElse(s -> true);

        String namespaceFilter = filters.stream()
            .filter(filter -> QueryFilter.Field.NAMESPACE.equals(filter.field()) && filter.value() != null)
            .map(QueryFilter::value)
            .map(String::valueOf)
            .findFirst()
            .orElse(null);

        Set<String> keys = new LinkedHashSet<>();

        // 1) 托管 secret：带 namespace EQUALS filter 时只列该 namespace 自己的（不含继承），
        //    否则列出全部命名空间的托管 key。
        if (namespaceFilter != null) {
            for (PostgresSecretStore.SecretRecord row : store.listOwn(tenantId, namespaceFilter)) {
                keys.add(row.key());
            }
        } else {
            store.allManagedKeys(tenantId).values().forEach(keys::addAll);
        }

        // 2) 环境变量 secret：全局可见（无 namespace 属性，现状兼容）。
        keys.addAll(envSecrets.keySet());

        List<String> filtered = keys.stream().filter(queryPredicate).toList();
        //noinspection unchecked
        return ArrayListTotal.of(pageable, new ArrayList<>(filtered));
    }

    @Override
    public Map<String, Set<String>> inheritedSecrets(String tenantId, String namespace) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        // 1) 托管 secret：链上各级 namespace 的 key 集合（本 namespace → 祖先）
        out.putAll(store.inheritedKeys(tenantId, namespace));
        // 2) 环境变量 secret：全局兜底，挂在最顶层（与 OSS 现状一致：任何 namespace 都可见）
        if (!envSecrets.isEmpty()) {
            List<String> chain = PostgresSecretStore.namespaceChain(namespace);
            out.put(chain.get(chain.size() - 1), envSecrets.keySet());
        }
        return out;
    }

    @Override
    public Map<String, Set<String>> ownAndInheritedSecrets(String tenantId, String namespace) throws IOException {
        Map<String, Set<String>> out = inheritedSecrets(tenantId, namespace);
        // 本 namespace 自己的托管 key 一定在 inheritedKeys 的首级中；无需额外合并。
        return out;
    }
}
