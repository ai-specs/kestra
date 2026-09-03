ARG BASE_IMAGE="ghcr.io/kestra-io/kestra-base:latest-no-plugins"
FROM ${BASE_IMAGE}

ENV PATH="/app/.venv/bin:$PATH"

COPY --chown=kestra:kestra docker /

RUN --mount=type=bind,target=/mnt/context \
    mkdir -p /app/plugins && \
    # Custom plugin jars live in locals/plugins/ (see .dockerignore: the plugin source
    # submodules are excluded from the build context, so /mnt/context/plugins does not exist).
    # Baking locals/plugins/ keeps the deepseek-harness jar (and any other custom jar) in sync
    # with local shadowJar builds — the base image carries no plugins of its own.
    { cp -r /mnt/context/locals/plugins/. /app/plugins/ 2>/dev/null || true; } && \
    chown -R kestra:kestra /app

USER kestra

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["--help"]
