#!/bin/bash

ffmpeg_notices_validate() {
  local lock_file="$1"
  local manifest="$2/notices/manifest"
  if [[ ! -f "${manifest}" || ! -r "${manifest}" || -L "${manifest}" ]]; then
    echo "Expected a regular readable FFmpeg notice manifest: ${manifest}" >&2
    return 1
  fi

  local key
  local expected
  local actual
  for key in release source_revision amd64_sha256 arm64_sha256; do
    expected="$(ffmpeg_lock_value "${lock_file}" "${key}")" || return
    actual="$(ffmpeg_lock_value "${manifest}" "${key}")" || return
    if [[ "${actual}" != "${expected}" ]]; then
      echo "FFmpeg notice inventory is stale (${key}); review sources and notices for the locked release" >&2
      return 1
    fi
  done
}
