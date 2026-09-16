#!/bin/bash

set -euo pipefail

: "${STREAMARR_WORKER_IMAGE:?Set the STREAMARR_WORKER_IMAGE repository variable to the verified standalone worker image}"
if [[ ! "${STREAMARR_WORKER_IMAGE}" =~ @sha256:[a-f0-9]{64}$ ]]; then
  echo 'STREAMARR_WORKER_IMAGE must use an immutable sha256 digest' >&2
  exit 1
fi
