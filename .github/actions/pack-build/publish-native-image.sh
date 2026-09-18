#!/usr/bin/env bash
set -euo pipefail

local_image=$1
architecture=$2
case "${architecture}" in
  amd64|arm64) ;;
  *) echo 'Unsupported native image architecture.' >&2; exit 1 ;;
esac

revision=$(git rev-parse HEAD)
image_repository=streamarr/streamarr-server
image="${image_repository}:sha-${revision}-${architecture}"
docker tag "${local_image}" "${image}"
docker push "${image}"
reference=$(docker image inspect --format '{{index .RepoDigests 0}}' "${image}")
digest=${reference##*@}
if [[ ! "${digest}" =~ ^sha256:[a-f0-9]{64}$ ]]; then
  echo 'The registry did not return a valid native image digest.' >&2
  exit 1
fi

jq -n --arg source "${revision}" --arg architecture "${architecture}" \
  --arg image "${image_repository}@${digest}" \
  '{sourceRevision: $source, architecture: $architecture, image: $image}' \
  > "${architecture}-image.json"
