ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-slim"
FROM ${BASE_IMAGE}

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

# DSH 插件白名单：完整覆盖被移除的 7 个 submodule（等价替换，功能不回退），
# 构建时从 Maven Central 拉取 LATEST。复用 `kestra plugins install`
# （与官方 kestra/kestra:* 镜像的发布流程一致，不自造轮子）。
# plugin-scripts 是聚合仓库：主模块 artifact 为 plugin-script（单数），
# 18 个语言子模块独立发布，全部纳入白名单保持等价。
# 需要更多官方插件时往列表加一行；不需要时删对应行。
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
    mkdir -p /app/plugins && \
    # 定制插件（plugin-deepseek-harness）本地烘焙：jar 放 locals/plugins/（构建上下文；
    # .dockerignore 排除插件源码 plugins/ 但保留 locals/）。base 镜像自身不带任何插件。
    { cp -r /mnt/context/locals/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    # 官方插件白名单：构建时从 Maven Central 解析 LATEST 并装入 /app/plugins。
    # 此 RUN 层会被 Docker 缓存；要强制刷新最新版请用 docker compose build --no-cache kestra。
    /app/kestra plugins install -p /app/plugins $PLUGIN_WHITELIST && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
