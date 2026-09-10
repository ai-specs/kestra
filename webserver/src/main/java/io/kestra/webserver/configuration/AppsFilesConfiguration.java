package io.kestra.webserver.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * dsh Apps 文件端点配置（{@code apps.files.*}）—— 约定根 namespace 绑定
 * （设计文档 {@code docs/dsh-apps-amis-editor.md} §6.1，审计 N1）。
 *
 * <p>文件端点没有 flow 可解析，{@code namespaceFactory.of(tenant, namespace, storage)}
 * 的 namespace 参数必须显式定义：{@link #rootNamespace} 即约定根 {@code apps/} 所在
 * namespace，文件端点一律用该值读写。声明 App 的 flow 必须与约定根同 namespace，
 * 否则 PageTrigger 渲染读 flow namespace 下的文件、编辑器写 root-namespace 下的文件，
 * "保存即时生效"会静默断裂（目录树构建时交叉比对并告警，见 AppsFileController）。
 */
@ConfigurationProperties("apps.files")
public class AppsFilesConfiguration {

    /** 约定根 apps/ 所在的 namespace（nsfile:/// 解析到该 namespace 的 storage）。 */
    private String rootNamespace = "dsh.apps";

    public String getRootNamespace() {
        return rootNamespace;
    }

    public void setRootNamespace(String rootNamespace) {
        this.rootNamespace = rootNamespace;
    }
}
