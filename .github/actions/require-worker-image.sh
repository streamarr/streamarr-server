#!/bin/bash

set -euo pipefail

repository=$(cd "$(dirname "$0")/../.." && pwd)
if [[ -z ${STREAMARR_WORKER_IMAGE:-} ]]; then
  source "$repository/worker-image.env"
fi
: "${STREAMARR_WORKER_IMAGE:?Set STREAMARR_WORKER_IMAGE in worker-image.env}"
if [[ ! "${STREAMARR_WORKER_IMAGE}" =~ @sha256:[a-f0-9]{64}$ ]]; then
  echo 'STREAMARR_WORKER_IMAGE must use an immutable sha256 digest' >&2
  exit 1
fi

if [[ -n ${GITHUB_ENV:-} ]]; then
  printf 'STREAMARR_WORKER_IMAGE=%s\n' "$STREAMARR_WORKER_IMAGE" >> "$GITHUB_ENV"
fi
