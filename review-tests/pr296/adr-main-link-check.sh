#!/usr/bin/env bash

set -euo pipefail

adr_repository="${STREAMARR_ADR_PATH:-/Users/stuckya/Projects/streamarr/streamarr-adr}"

git -C "${adr_repository}" cat-file \
  -e origin/main:adr/0022-single-home-accounts-and-portable-profile-sharing.adoc

git -C "${adr_repository}" cat-file \
  -e origin/main:adr/diagrams/0022-portable-profile-sharing.png
