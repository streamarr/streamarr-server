#!/bin/bash
# Runs the Cedar engine self-check inside the built image so a native library that fails to load
# on this CPU architecture fails the packaging job, not the first real authorization in production.

set -euo pipefail

image="${1:?Usage: verify-cedar-image.sh <image>}"

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  docker pull "${image}" >/dev/null
fi

output="$(docker run --rm --entrypoint /cnb/lifecycle/launcher "${image}" \
  java --enable-native-access=ALL-UNNAMED \
    -Dloader.main=com.streamarr.server.services.authorization.cedar.CedarEngineSelfCheckLauncher \
    org.springframework.boot.loader.launch.PropertiesLauncher)"

echo "${output}"
grep -F "Cedar self-check passed: permittedAllowed=true strangerDenied=true" <<<"${output}" >/dev/null
