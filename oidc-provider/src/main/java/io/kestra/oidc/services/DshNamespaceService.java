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

    /** 云电脑模式全局执行 flow 的命名空间（docs/dsh-云电脑模式需求规格.md §4.1：dsh.exec/exec-run）。 */
    static final String EXEC_FLOW_NAMESPACE = "dsh.exec";

    /** 员工命名空间前缀（规格 §4.2：employee.{slug}-{hash8}）。 */
    static final String EMPLOYEE_NAMESPACE_PREFIX = "employee.";

    @Inject
    public DshNamespaceService(Provider<FlowMetaStoreInterface> flowMetaStore) {
        super(flowMetaStore);
    }

    @Override
    public boolean isAllowedNamespace(String tenant, String namespace, String fromTenant, String fromNamespace) {
        // 目标 namespace 必须在来源 namespace 的继承链上（含自身）：
        // from=a.b.c 访问 a / a.b / a.b.c → 允许（子读祖先）；访问 b / a.b.x 等链外 → 拒绝。
        if (PostgresSecretStore.namespaceChain(fromNamespace).contains(namespace)) {
            return true;
        }
        // 例外（云电脑模式，规格 §3-B/§4.3/§4.9）：全局执行 flow（dsh.exec/exec-run）按注入的
        // employeeNamespace 挂载/回写员工 namespace 存储——共享 flow 是所有员工 namespace 的
        // 「链外」访问者，链内语义天然不覆盖。此处的授权点不在本闸门：手机端唯一触发面是
        // DshExecController 封装端点（白名单 flow + namespace 从 token sub 推导、请求体伪造一律
        // 忽略 + sub == 所有者）；原生管理面直接触发属管理员信任边界（与现状一致）。仅放行
        // dsh.exec → employee.* 单一方向，其余链外访问维持拒绝。
        return EXEC_FLOW_NAMESPACE.equals(fromNamespace) && namespace.startsWith(EMPLOYEE_NAMESPACE_PREFIX);
    }
}
