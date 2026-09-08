#!/bin/bash

ffmpeg_materials_validate() (
  cd "$1" || return
  local sums=generated/SHA256SUMS
  if [[ ! -f "${sums}" || -L "${sums}" ]]; then
    echo "Expected regular FFmpeg redistribution checksums: ${sums}" >&2
    return 1
  fi

  if [[ -n "$(find notices generated -type l -print)" ]]; then
    echo "FFmpeg redistribution materials must not contain symlinks" >&2
    return 1
  fi

  local expected
  expected="$(
    printf '%s\n' ffmpeg.lock LICENSE.txt SOURCE.txt \
      generated/THIRD-PARTY-NOTICES.txt generated/ffmpeg.amd64.cdx.json \
      generated/ffmpeg.arm64.cdx.json generated/review-inputs.json
    find notices -type f -print
  )" || return
  if ! diff -u <(printf '%s\n' "${expected}" | LC_ALL=C sort) \
    <(cut -d ' ' -f3- "${sums}" | LC_ALL=C sort) >&2; then
    echo "FFmpeg redistribution checksum inventory mismatch; every input must be covered exactly once" >&2
    return 1
  fi

  local verification
  if ! verification="$(ffmpeg_sha256_check --check --strict "${sums}" 2>&1)"; then
    printf '%s\n' "${verification}" >&2
    echo "FFmpeg redistribution materials are stale; review inputs and run buildpacks/ffmpeg/bin/prepare" >&2
    return 1
  fi
)
