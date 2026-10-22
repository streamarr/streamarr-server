#!/bin/bash
# Proves the image raises the memory calculator's class estimate, which sets -XX:MaxMetaspaceSize,
# by the given percentage, and that BPL_JVM_CLASS_ADJUSTMENT set at run time still replaces it.

set -euo pipefail

usage='Usage: verify-metaspace-image.sh <image> <class-adjustment-percent>'
image="${1:?${usage}}"
adjustment="${2:?${usage}}"

if ! docker image inspect "${image}" >/dev/null 2>&1; then
  docker pull "${image}" >/dev/null
fi

loaded_class_count() {
  local configuration
  configuration="$(docker run --rm "$@" --entrypoint /cnb/lifecycle/launcher "${image}" true \
    | grep -F 'Calculated JVM Memory Configuration:')"
  echo "${configuration}" >&2
  sed -nE 's/.*Loaded Class Count: ([0-9]+).*/\1/p' <<<"${configuration}"
}

image_count="$(loaded_class_count)"
unadjusted_count="$(loaded_class_count --env BPL_JVM_CLASS_ADJUSTMENT=100%)"

# The calculator truncates both counts to whole classes.
difference=$(( image_count * 100 - unadjusted_count * adjustment ))
tolerance=$(( adjustment > 100 ? adjustment : 100 ))
if (( difference < 0 )); then
  difference=$(( -difference ))
fi

if (( difference < tolerance )); then
  exit 0
fi

echo "Expected the image's loaded class count ${image_count} to be ${adjustment}% of the" \
  "run-time BPL_JVM_CLASS_ADJUSTMENT=100% count ${unadjusted_count}" >&2
exit 1
