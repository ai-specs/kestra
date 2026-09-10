ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-slim"
FROM ${BASE_IMAGE}

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

# DSH 插件白名单：完整覆盖被移除的 7 个 submodule（等价替换，功能不回退），
# 构建时从 Maven 拉取 LATEST。复用 `kestra plugins install`
# （与官方 kestra/kestra:* 镜像的发布流程一致，不自造轮子）。
# plugin-scripts 是聚合仓库：主模块 artifact 为 plugin-script（单数），
# 18 个语言子模块独立发布，全部纳入白名单保持等价。
# 需要更多官方插件时往列表加一行；不需要时删对应行。
#
# 额外 Maven 仓库（国内源）：容器内的 kestra CLI 不读宿主机 ~/.m2/settings.xml /
# ~/.gradle 的镜像配置，默认只认 Maven Central；国内直连 central 常被重置
# （SSL_ERROR_SYSCALL / Remote host terminated the handshake），且 Docker Desktop
# 配置的本地代理失效时 build 内同样不可达。经 `--repositories` 追加国内镜像
# （如 https://maven.aliyun.com/repository/public），与 central 并存、任一可解析即成功。
# 置空则只用 central。
ARG PLUGIN_REPOSITORIES=""
ARG PLUGIN_WHITELIST="\
io.kestra.plugin:plugin-script:LATEST \
io.kestra.plugin:plugin-script-bun:LATEST \
io.kestra.plugin:plugin-script-deno:LATEST \
io.kestra.plugin:plugin-script-dotnet:LATEST \
io.kestra.plugin:plugin-script-go:LATEST \
io.kestra.plugin:plugin-script-groovy:LATEST \
io.kestra.plugin:plugin-script-jbang:LATEST \
io.kestra.plugin:plugin-script-julia:LATEST \
io.kestra.plugin:plugin-script-jython:LATEST \
io.kestra.plugin:plugin-script-lua:LATEST \
io.kestra.plugin:plugin-script-nashorn:LATEST \
io.kestra.plugin:plugin-script-node:LATEST \
io.kestra.plugin:plugin-script-perl:LATEST \
io.kestra.plugin:plugin-script-php:LATEST \
io.kestra.plugin:plugin-script-powershell:LATEST \
io.kestra.plugin:plugin-script-python:LATEST \
io.kestra.plugin:plugin-script-r:LATEST \
io.kestra.plugin:plugin-script-ruby:LATEST \
io.kestra.plugin:plugin-script-shell:LATEST \
io.kestra.plugin:plugin-serdes:LATEST \
io.kestra.plugin:plugin-fs:LATEST \
io.kestra.plugin:plugin-kestra:LATEST \
io.kestra.plugin:plugin-notifications:LATEST \
io.kestra.plugin:plugin-deepseek:LATEST \
io.kestra.plugin:plugin-openai:LATEST \
io.kestra.plugin:plugin-ai:LATEST"

RUN --mount=type=bind,target=/mnt/context \
    --mount=type=cache,target=/kestra-m2-cache,sharing=locked,id=kestra-plugins-m2 \
    mkdir -p /app/plugins && \
    # 定制插件（plugin-deepseek-harness）本地烘焙：jar 放 locals/plugins/（构建上下文；
    # .dockerignore 排除插件源码 plugins/ 但保留 locals/）。base 镜像自身不带任何插件。
    { cp -r /mnt/context/locals/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    # 官方插件白名单：构建时解析 LATEST 并装入 /app/plugins（默认 Maven Central；
    # PLUGIN_REPOSITORIES 非空时经 --repositories 追加国内镜像）。
    # 此 RUN 层会被 Docker 缓存；要强制刷新最新版请用 docker compose build --no-cache kestra。
    # 构建期注入占位 dsh.metrics 配置：`plugins install` 会启动完整 Kestra 上下文，
    # 其 @Scheduled bean（DshGoldenMetricsBinder）需要 dsh.metrics.jdbc-url 等属性，
    # 缺省时 bean 创建失败导致 install 以非零退出（构建期无 DB，占位值不会被真正使用）。
    # 本地 m2 仓库钉死在 cache mount：网络抖动导致 install 中断时，已下载的构件跨次
    # 构建保留，重试即增量续传，不必每次从零下载。
    # 把默认 central（application.yml 注入，repo.maven.apache.org）的 url 覆盖为国内镜像：
    # aether 按 [central, --repositories 追加] 顺序解析，central 排第一且国内直连常被
    # 重置——只追加不覆盖时 jar 下载仍先打 central，随机构件会失败。
    KESTRA_CONFIGURATION=$'dsh:\n  metrics:\n    jdbc-url: jdbc:postgresql://127.0.0.1:5432/build\n    jdbc-username: build\n    jdbc-password: build\nkestra:\n  plugins:\n    local-repository-path: /kestra-m2-cache\n    repositories:\n      central:\n        url: https://repo.huaweicloud.com/repository/maven' \
    /app/kestra plugins install -p /app/plugins $PLUGIN_WHITELIST \
        $(if [ -n "$PLUGIN_REPOSITORIES" ]; then echo "--repositories $PLUGIN_REPOSITORIES"; fi) && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
