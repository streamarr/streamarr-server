#!/usr/bin/env bash

set -euo pipefail

base_ref="${PR296_BASE_REF:-c804741e2bd8213e1da30feea01648767d80e787^}"

git diff --unified=0 "${base_ref}" -- \
  | awk '
      /^\+\+\+/ { next }
      /^\+/ {
        added++
        if ($0 ~ /^\+[[:space:]]*(\/\*\*|\*|\/\/)/) {
          comments++
        }
      }
      END {
        added += 0
        comments += 0
        print "added_lines=" added ", added_comment_lines=" comments
        exit added > 2000
      }
    '
