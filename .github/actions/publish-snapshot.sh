#!/usr/bin/env bash
set -euo pipefail

if [[ ${GITHUB_EVENT_NAME:-} != push || ${GITHUB_REF:-} != refs/heads/main \
  || ${GITHUB_REPOSITORY:-} != streamarr/streamarr-server ]]; then
  echo 'Image publication requires a reviewed main push.' >&2
  exit 1
fi
if [[ ! ${GITHUB_SHA:-} =~ ^[a-f0-9]{40}$ ]]; then
  echo 'Image publication requires a full source revision.' >&2
  exit 1
fi

image_repository=streamarr/streamarr-server
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").find("{*}version").text)')
if [[ ! "${version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$ ]]; then
  echo 'Maven version must be X.Y.Z or X.Y.Z-SNAPSHOT.' >&2
  exit 1
fi

references=()
for architecture in amd64 arm64; do
  if ! reference=$(jq -er --arg source "${GITHUB_SHA}" --arg architecture "${architecture}" \
    'select(.sourceRevision == $source and .architecture == $architecture) | .image |
    select(test("^streamarr/streamarr-server@sha256:[a-f0-9]{64}$"))' "${architecture}-image.json"); then
    echo "Invalid native image receipt for ${architecture}." >&2
    exit 1
  fi
  references+=("${reference}")
done

docker buildx imagetools create --metadata-file image-metadata.json \
  --tag "${image_repository}:sha-${GITHUB_SHA}" "${references[@]}"
digest=$(jq -er '."containerimage.descriptor".digest' image-metadata.json)
if [[ ! "${digest}" =~ ^sha256:[a-f0-9]{64}$ ]]; then
  echo 'The registry did not return a valid manifest digest.' >&2
  exit 1
fi

docker buildx imagetools inspect --raw "${image_repository}@${digest}" > image-index.json
jq -e '[.manifests[].platform | .os + "/" + .architecture] | sort == ["linux/amd64", "linux/arm64"]' image-index.json

if [[ "${version}" != *-SNAPSHOT ]]; then
  echo "Stable image tags are published by the release workflow."
  exit 0
fi

main_revision=$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" --jq '.object.sha')
if [[ ! "${main_revision}" =~ ^[a-f0-9]{40}$ ]]; then
  echo 'GitHub did not return a valid main revision.' >&2
  exit 1
fi
if [[ "${main_revision}" != "${GITHUB_SHA}" ]]; then
  echo "Keeping ${version} unchanged because ${GITHUB_SHA} is no longer main."
  exit 0
fi

docker buildx imagetools create --tag "${image_repository}:${version}" "${image_repository}@${digest}"
