#!/bin/bash

ffmpeg_materials_validate() (
  cd "$1" || return
  local sums=generated/SHA256SUMS
  if [[ ! -f "${sums}" || -L "${sums}" ]]; then
    echo "Expected regular FFmpeg redistribution checksums: ${sums}" >&2
    return 1
  fi

  local required
  for required in ffmpeg.lock LICENSE.txt SOURCE.txt notices/sources.json \
    notices/buildconf-amd64.txt notices/buildconf-arm64.txt \
    generated/THIRD-PARTY-NOTICES.txt generated/ffmpeg.amd64.cdx.json \
    generated/ffmpeg.arm64.cdx.json generated/review-inputs.json; do
    if ! grep -Eq "^[0-9a-f]{64}  ${required//./\\.}$" "${sums}"; then
      echo "FFmpeg redistribution checksum missing: ${required}" >&2
      return 1
    fi
  done

  if ! sha256sum --check --strict "${sums}" >/dev/null; then
    echo "FFmpeg redistribution materials are stale; review inputs and run bin/generate-notices.mjs" >&2
    return 1
  fi
)
