#!/usr/bin/env sh

set -e

# Kestra entrypoint — two jar shapes are supported:
#   1. gradle `executableJar` (bun run build:kestra): self-run sh header + zip — exec directly.
#   2. plain `shadowJar` output (no header, starts with the PK zip magic) — fall back to
#      `java -jar` with the same environment preparation and JVM flags as selfrun.sh.
# The fallback exists so a manually built headerless jar can never brick the container
# (OOM investigation §8.14.3: that failure mode cost three recovery attempts once).

if head -c 2 /app/kestra 2>/dev/null | grep -q 'PK'; then
    # headerless shadowJar — replicate gradle/jar/selfrun.sh env + flags
    KESTRA_PLUGINS_PATH="${KESTRA_PLUGINS_PATH:-/app/plugins}"
    export KESTRA_PLUGINS_PATH
    KESTRA_CONFIGURATION_PATH="${KESTRA_CONFIGURATION_PATH:-/app/confs}"
    if [ -n "${KESTRA_CONFIGURATION}" ]; then
        echo "${KESTRA_CONFIGURATION}" > "${KESTRA_CONFIGURATION_PATH}/application.yml"
        export MICRONAUT_CONFIG_FILES="${KESTRA_CONFIGURATION_PATH}/application.yml"
    fi
    exec java \
        -XX:MaxRAMPercentage=50.0 \
        --add-opens java.base/java.nio=ALL-UNNAMED \
        --add-opens java.base/java.util=ALL-UNNAMED \
        --add-opens java.base/java.lang=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        --enable-native-access=ALL-UNNAMED \
        ${JAVA_OPTS} \
        -jar /app/kestra "$@"
fi

exec /app/kestra "$@"
