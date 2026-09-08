package io.kestra.oidc.services;

import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.services.DefaultNamespaceService;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * 叠加版 NamespaceService（dsh OSS 自实现，仿企业版隔离语义）。
 *
 * <p>
 * Kestra 的 {@code NamespaceService#checkAllowedNamespace} 是所有跨 namespace 资源访问
 * （secret 函数、storage 文件、KV store、flow 引用等）的统一授权闸门，但 OSS 的
 * {@link DefaultNamespaceService} 实现恒放行（注释明示"namespace management is an EE
 * feature"）。本类以 {@code @Replaces} 无侵入增强为真正的链内隔离：
 *
 * <ul>
 *   <li>目标 namespace 在来源 namespace 的继承链上（含自身）→ 允许（子可访问祖先资源）；</li>
 *   <li>链外（兄弟 / 无关 namespace）→ 拒绝。</li>
 * </ul>
 *
 * <p>
 * 该语义与 {@link PostgresSecretStore#find} 的读取链一致：flow 默认用自己的 namespace
 * 查询，显式指定其它 namespace 时先经本闸门校验——两层共同构成用户确认的
 * "不同 namespace 的 flow 不跨 namespace 获取 secrets" 隔离。
 *
 * <p>
 * 仅随 managed secret 功能一并启用（加密密钥已配置）；未配置时行为与 OSS 一致（恒放行）。
 */
@Singleton
@Replaces(DefaultNamespaceService.class)
@Requires(bean = PostgresSecretStore.class)
public class DshNamespaceService extends DefaultNamespaceService {

    @Inject
    public DshNamespaceService(Provider<FlowMetaStoreInterface> flowMetaStore) {
        super(flowMetaStore);
    }

    @Override
    public boolean isAllowedNamespace(String tenant, String namespace, String fromTenant, String fromNamespace) {
        // 目标 namespace 必须在来源 namespace 的继承链上（含自身）：
        // from=a.b.c 访问 a / a.b / a.b.c → 允许（子读祖先）；访问 b / a.b.x 等链外 → 拒绝。
        return PostgresSecretStore.namespaceChain(fromNamespace).contains(namespace);
    }
}
