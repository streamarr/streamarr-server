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

filename_encoding() {
  docker run --rm "$@" --entrypoint /cnb/lifecycle/launcher "${image}" \
    java -XshowSettings:properties -version 2>&1 \
    | sed -nE 's/^ *sun\.jnu\.encoding = (.*)$/\1/p'
}

# Java converts filenames through sun.jnu.encoding, which follows the process locale. The image
# defaults LANG to C.UTF-8, and an operator's LC_ALL must still override that default.
verify_filename_encoding() {
  local expected="$1"
  local condition="$2"
  shift 2
  local actual
  actual="$(filename_encoding "$@")"
  if [[ "${actual}" == "${expected}" ]]; then
    return
  fi

  echo "Expected sun.jnu.encoding=${expected} ${condition} but found ${actual}" >&2
  exit 1
}

verify_filename_encoding UTF-8 'with no locale set'
verify_filename_encoding ANSI_X3.4-1968 'under LC_ALL=POSIX' --env LC_ALL=POSIX
