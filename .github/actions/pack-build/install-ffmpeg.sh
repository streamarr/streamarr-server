#!/bin/bash

set -euo pipefail

destination="${1:?Usage: install-ffmpeg.sh <destination>}"
release=autobuild-2026-07-31-14-10
version=n8.1.2-34-g9b6c8969e0

case "$(uname -m)" in
  x86_64)
    platform=linux64
    checksum=09fc77be269c7053e438b7e96548e4af97604faf96a42c4a3c56a1ad74c22c0a
    ;;
  aarch64 | arm64)
    platform=linuxarm64
    checksum=177e40c91564dec3840096f3bf1ffe696b94330585972462cfc739fa29fe0e1a
    ;;
  *)
    echo "Unsupported FFmpeg target architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

if [[ -e "${destination}" ]]; then
  echo "FFmpeg destination already exists: ${destination}" >&2
  exit 1
fi

asset="ffmpeg-${version}-${platform}-gpl-8.1.tar.xz"
download_url="https://github.com/BtbN/FFmpeg-Builds/releases/download/${release}/${asset}"
download_dir="$(mktemp -d)"
archive="${download_dir}/${asset}"

curl \
  --fail \
  --location \
  --proto '=https' \
  --proto-redir '=https' \
  --retry 3 \
  --retry-all-errors \
  --silent \
  --show-error \
  "${download_url}" \
  --output "${archive}"
printf '%s  %s\n' "${checksum}" "${archive}" | sha256sum --check --strict

mkdir -p "${destination}/bin"
tar \
  --extract \
  --xz \
  --file "${archive}" \
  --directory "${destination}/bin" \
  --strip-components=2 \
  "ffmpeg-${version}-${platform}-gpl-8.1/bin/ffmpeg" \
  "ffmpeg-${version}-${platform}-gpl-8.1/bin/ffprobe"
tar \
  --extract \
  --xz \
  --file "${archive}" \
  --directory "${destination}" \
  --strip-components=1 \
  "ffmpeg-${version}-${platform}-gpl-8.1/LICENSE.txt"

printf '%s\n' \
  "FFmpeg ${version}-20260731" \
  "Binary release: https://github.com/BtbN/FFmpeg-Builds/releases/tag/${release}" \
  "Build source: https://github.com/BtbN/FFmpeg-Builds/tree/${release}" \
  "FFmpeg source: https://github.com/FFmpeg/FFmpeg/commit/9b6c8969e0" \
  >"${destination}/SOURCE.txt"

version_output="$("${destination}/bin/ffmpeg" -version 2>&1)"
grep -F "ffmpeg version ${version}-20260731" <<<"${version_output}" >/dev/null
grep -F -- "--disable-libfdk-aac" <<<"${version_output}" >/dev/null
"${destination}/bin/ffmpeg" -hide_banner -h muxer=hls 2>&1 \
  | grep -F -- "-hls_segment_options" >/dev/null
