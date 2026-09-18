#!/bin/bash

set -euo pipefail

image="${1:?Usage: verify-server-image.sh <image> <version> <source> <revision>}"
expected_version="${2:?Usage: verify-server-image.sh <image> <version> <source> <revision>}"
expected_source="${3:?Usage: verify-server-image.sh <image> <version> <source> <revision>}"
expected_revision="${4:?Usage: verify-server-image.sh <image> <version> <source> <revision>}"

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  docker pull "${image}" >/dev/null
fi

verify_label() {
  local label="$1"
  local expected="$2"
  local actual
  actual="$(docker image inspect --format "{{ index .Config.Labels \"${label}\" }}" "${image}")"
  if [[ "${actual}" == "${expected}" ]]; then
    return
  fi

  echo "Expected ${label}=${expected} but found ${actual}" >&2
  exit 1
}

verify_label org.opencontainers.image.version "${expected_version}"
verify_label org.opencontainers.image.source "${expected_source}"
verify_label org.opencontainers.image.revision "${expected_revision}"

docker run --rm --interactive --entrypoint /cnb/lifecycle/launcher "$image" \
  /bin/bash -euo pipefail -s <<'SCRIPT'
  if command -v ffmpeg >/dev/null || command -v ffprobe >/dev/null; then
    echo "Server image must not contain FFmpeg or ffprobe" >&2
    exit 1
  fi
SCRIPT
