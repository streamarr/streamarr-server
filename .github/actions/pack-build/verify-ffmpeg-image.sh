#!/bin/bash

set -euo pipefail

image="${1:?Usage: verify-ffmpeg-image.sh <image>}"

docker run --rm --entrypoint /bin/bash "${image}" -euo pipefail -c '
  ffmpeg=/workspace/.ffmpeg/bin/ffmpeg
  ffprobe=/workspace/.ffmpeg/bin/ffprobe

  version_output="$(${ffmpeg} -version 2>&1)"
  grep -F "ffmpeg version n8.1.2-34-g9b6c8969e0-20260731" <<<"${version_output}" >/dev/null
  grep -F -- "--enable-gpl" <<<"${version_output}" >/dev/null
  grep -F -- "--disable-libfdk-aac" <<<"${version_output}" >/dev/null
  if grep -F -- "--enable-nonfree" <<<"${version_output}"; then
    echo "FFmpeg must not include nonfree components" >&2
    exit 1
  fi

  "${ffmpeg}" -hide_banner -h muxer=hls 2>&1 \
    | grep -F -- "-hls_segment_options" >/dev/null

  output_dir="$(mktemp -d)"
  "${ffmpeg}" \
    -hide_banner \
    -loglevel error \
    -f lavfi \
    -i testsrc2=size=320x180:rate=30 \
    -f lavfi \
    -i sine=frequency=1000:sample_rate=48000 \
    -t 3 \
    -c:v libx264 \
    -pix_fmt yuv420p \
    -c:a aac \
    -f hls \
    -hls_time 1 \
    -hls_list_size 0 \
    -hls_flags temp_file \
    -hls_segment_type fmp4 \
    -hls_fmp4_init_filename init.mp4 \
    -hls_segment_options movflags=+frag_discont \
    -hls_segment_filename "${output_dir}/segment%d.m4s" \
    "${output_dir}/playlist.m3u8"

  test -s "${output_dir}/init.mp4"
  test -s "${output_dir}/segment0.m4s"
  grep -F "#EXT-X-MAP:URI=\"init.mp4\"" "${output_dir}/playlist.m3u8"
  grep -F "segment0.m4s" "${output_dir}/playlist.m3u8"
  "${ffprobe}" \
    -v error \
    -show_entries format=format_name \
    -of default=noprint_wrappers=1 \
    "${output_dir}/playlist.m3u8" \
    | grep -F "format_name=hls"
'

docker run --rm --entrypoint /cnb/lifecycle/launcher "${image}" \
  ffmpeg -version >/dev/null
