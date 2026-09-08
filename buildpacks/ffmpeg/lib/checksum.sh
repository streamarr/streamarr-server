#!/bin/bash

ffmpeg_sha256_check() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$@"
    return
  fi
  echo "FFmpeg checksum validation requires sha256sum or shasum" >&2
  return 1
}
