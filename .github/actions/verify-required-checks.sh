#!/bin/bash

set -euo pipefail

if (( $# != 5 )); then
  echo "Usage: $0 <changes> <packaging> <application> <package-image> <analysis>" >&2
  exit 2
fi

changes_result="$1"
packaging_required="$2"
application_result="$3"
package_image_result="$4"
analysis_result="$5"
if [[ "${changes_result}" != "success" ]]; then
  echo "Change detection failed: ${changes_result}" >&2
  exit 1
fi
if [[ "${packaging_required}" != "true" && "${packaging_required}" != "false" ]]; then
  echo "Invalid packaging change result: ${packaging_required}" >&2
  exit 1
fi
if [[ "${application_result}" != "success" ]]; then
  echo "Application verification failed: ${application_result}" >&2
  exit 1
fi
if [[ "${packaging_required}" == "true" && "${package_image_result}" != "success" ]]; then
  echo "Package image verification failed: ${package_image_result}" >&2
  exit 1
fi
if [[ "${packaging_required}" == "false" \
  && "${package_image_result}" != "success" \
  && "${package_image_result}" != "skipped" ]]; then
  echo "Unexpected package image result: ${package_image_result}" >&2
  exit 1
fi
if [[ "${analysis_result}" != "success" ]]; then
  echo "Coverage and analysis verification failed: ${analysis_result}" >&2
  exit 1
fi
