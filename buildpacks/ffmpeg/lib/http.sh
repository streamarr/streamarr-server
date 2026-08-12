#!/bin/bash

ffmpeg_curl() {
  local attempt=0
  local status
  while true; do
    status=0
    curl "$@" || status=$?
    if (( status == 0 )); then
      return
    fi
    case "${status}" in
      5 | 6 | 7 | 18 | 35 | 52 | 55 | 56 | 92) ;;
      *) return "${status}" ;;
    esac
    ((attempt += 1))
    if (( attempt > 3 )); then
      return "${status}"
    fi
    sleep "${attempt}"
  done
}
