#!/bin/bash

ffmpeg_lock_keys=(
  release
  version
  source_revision
  asset_variant
  amd64_asset
  amd64_sha256
  arm64_asset
  arm64_sha256
)

ffmpeg_lock_value() {
  local lock_file="$1"
  local key="$2"
  local line
  local values=()
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == "${key}="* ]]; then
      values+=("${line#*=}")
    fi
  done <"${lock_file}"
  if (( ${#values[@]} != 1 )) || [[ -z "${values[0]}" ]]; then
    echo "Expected exactly one ${key} entry in ${lock_file}" >&2
    return 1
  fi
  printf '%s' "${values[0]}"
}

ffmpeg_lock_validate() {
  local lock_file="$1"
  local key
  for key in "${ffmpeg_lock_keys[@]}"; do
    ffmpeg_lock_value "${lock_file}" "${key}" >/dev/null || return
  done

  local line
  local line_count=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    ((line_count += 1))
  done <"${lock_file}"
  if (( line_count != ${#ffmpeg_lock_keys[@]} )); then
    echo "Expected ${lock_file} to contain exactly ${#ffmpeg_lock_keys[@]} lock entries" >&2
    return 1
  fi

  local release
  local version
  local source_revision
  local asset_variant
  local amd64_asset
  local amd64_sha256
  local arm64_asset
  local arm64_sha256
  release="$(ffmpeg_lock_value "${lock_file}" release)"
  version="$(ffmpeg_lock_value "${lock_file}" version)"
  source_revision="$(ffmpeg_lock_value "${lock_file}" source_revision)"
  asset_variant="$(ffmpeg_lock_value "${lock_file}" asset_variant)"
  amd64_asset="$(ffmpeg_lock_value "${lock_file}" amd64_asset)"
  amd64_sha256="$(ffmpeg_lock_value "${lock_file}" amd64_sha256)"
  arm64_asset="$(ffmpeg_lock_value "${lock_file}" arm64_asset)"
  arm64_sha256="$(ffmpeg_lock_value "${lock_file}" arm64_sha256)"

  if [[ ! "${release}" =~ ^autobuild-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}$ ]]; then
    echo "FFmpeg lock release is not an exact BtbN autobuild tag" >&2
    return 1
  fi
  if [[ ! "${source_revision}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "FFmpeg lock source revision is not a full commit SHA" >&2
    return 1
  fi
  if [[ "${version}" =~ ^n[A-Za-z0-9.+-]+-g([0-9a-f]{7,40})$ ]]; then
    if [[ "${source_revision}" != "${BASH_REMATCH[1]}"* ]]; then
      echo "FFmpeg lock source revision contradicts version" >&2
      return 1
    fi
  elif [[ ! "${version}" =~ ^n[0-9]+(\.[0-9]+)+$ ]]; then
    echo "FFmpeg lock version is unsupported: ${version}" >&2
    return 1
  fi
  if [[ ! "${asset_variant}" =~ ^gpl-[0-9]+\.[0-9]+$ \
    || "${amd64_asset}" != "ffmpeg-${version}-linux64-${asset_variant}.tar.xz" \
    || "${arm64_asset}" != "ffmpeg-${version}-linuxarm64-${asset_variant}.tar.xz" ]]; then
    echo "FFmpeg lock asset contradicts version and variant" >&2
    return 1
  fi
  if [[ ! "${amd64_sha256}" =~ ^[0-9a-f]{64}$ \
    || ! "${arm64_sha256}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "FFmpeg lock checksum is not SHA-256" >&2
    return 1
  fi
}
