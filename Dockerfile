ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-slim"
FROM ${BASE_IMAGE}

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

# 官方插件与本地定制插件均为**宿主预编译产物**，本镜像只打包、不在构建期下载：
#   - 官方插件：bun run build:plugins-official（宿主机 kestra plugins install，
#     m2 缓存免重下载）→ 产物落 docker/app/plugins/（.gitignore 忽略，仅跟踪 .gitkeep）；
#   - 定制插件：bake-plugins 烘焙到 locals/plugins/，此处合并进 /app/plugins。
# 产物新鲜由宿主预编译（preup 的 build:kestra / build:plugins）保证；
# 镜像新鲜由 compose pull_policy: build 保证。
# 强制刷新官方插件 LATEST 是 CI/PR（Dockerfile.pr）的语义（CI 无缓存天然新鲜），
# dev 路径无需 docker build --no-cache。
RUN --mount=type=bind,target=/mnt/context \
    mkdir -p /app/plugins && \
    { cp -r /mnt/context/locals/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
