#!/usr/bin/env bash
set -euo pipefail

module=buf.build/streamarr/transcode
commit=$(buf registry module commit resolve "$module:main" --format json | jq -er '.commit')

descriptors=$(mktemp -d)
trap 'rm -rf "$descriptors"' EXIT
buf build . --as-file-descriptor-set --exclude-source-info --output "$descriptors/local.binpb"
buf build "$module:$commit" --as-file-descriptor-set --exclude-source-info --output "$descriptors/published.binpb"
cmp "$descriptors/local.binpb" "$descriptors/published.binpb"

sdk_version=$(buf registry sdk version --module="$module:$commit" --plugin=buf.build/grpc/java:v1.84.0)

set --
if [[ -n "${BUF_TOKEN:-}" ]]; then
  set -- --settings .github/buf-consumer/settings.xml
fi
./mvnw "$@" --batch-mode --file .github/buf-consumer/pom.xml -Dbuf.sdk.version="$sdk_version" verify

mkdir -p target
printf 'module=%s\ncommit=%s\ngrpc.sdk.version=%s\n' "$module" "$commit" "$sdk_version" \
  > target/buf-consumer-pin.properties
