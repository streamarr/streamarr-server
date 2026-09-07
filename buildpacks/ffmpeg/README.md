# FFmpeg runtime lock

`release` is the only Renovate-owned input. `ffmpeg.lock` is generated from that exact
[Jellyfin FFmpeg release](https://github.com/jellyfin/jellyfin-ffmpeg/releases) and pins the
full package version, fork source commit, Linux amd64/arm64 portable GPL assets, and SHA-256
digests. Builds consume only this lock: no floating `latest`, distro FFmpeg, or daily autobuilds.
See [ADR 0029](https://github.com/streamarr/streamarr-adr/blob/main/adr/0029-pin-jellyfin-ffmpeg-releases.adoc).

Regenerate or validate the lock with:

```bash
buildpacks/ffmpeg/bin/update-lock
buildpacks/ffmpeg/bin/update-lock --release v8.1.2-4
buildpacks/ffmpeg/bin/update-lock --check
buildpacks/ffmpeg/bin/update-lock --verify-upstream
```

The updater requires Bash, `curl`, and `jq`. It accepts only published, non-prerelease tags
in `vMAJOR.MINOR.PATCH-BUILD` form, requires exactly one portable GPL asset and a GitHub
SHA-256 digest per architecture, resolves the tag's full fork commit, and writes the lock
atomically. `--check` validates the lock offline; `--verify-upstream` regenerates canonical
metadata from GitHub and compares it byte-for-byte without modifying the lock.

The buildpack verifies the downloaded archive against the locked checksum before extracting
its root-level `ffmpeg` and `ffprobe` binaries. Jellyfin's banner omits the packaging revision:
package `8.1.2-4` reports `8.1.2-Jellyfin`; the checksum pins the exact build. The runtime must
enable GPL, exclude nonfree components, and support `hls_segment_options`. Jellyfin's GPL
build uses stripped FDK-AAC, so `--enable-libfdk-aac` alone is not a nonfree-build indicator.
The layer includes GPLv3 text, release/source attribution, and a CycloneDX SBOM.

## Renovate synchronization

Renovate tracks `jellyfin/jellyfin-ffmpeg` with the `github-releases` datasource and
[regex versioning](https://docs.renovatebot.com/modules/versioning/regex/). The fourth numeric
component uses the `build` capture group: `-10` sorts after `-9`, and packaging-only fixes
are stable patch updates, not SemVer prereleases. Updates have their own PR and are not
automerged.

`.github/workflows/sync-ffmpeg-lock.yml` uses `pull_request_target` only for same-repository
Renovate PRs. It executes resolver code from the trusted PR base, reads the proposed release
from a Git object as data, and generates the lock entirely in the trusted checkout. It never
executes proposed code with write credentials. Only after detecting a changed lock and
verifying the head SHA does it mint a short-lived GitHub App token.

GitHub's `createCommitOnBranch` API creates a signed commit containing only the generated lock.
Its `expectedHeadOid` check rejects a moved branch atomically. The App token triggers normal
PR checks after the commit; the default Actions token would suppress those runs.

Install a GitHub App on this repository with repository contents read/write access and configure
these Actions secrets:

- `FFMPEG_LOCK_APP_CLIENT_ID`
- `FFMPEG_LOCK_APP_PRIVATE_KEY`

Renovate's `gitIgnoredAuthors` includes the App's API-authored noreply email
(`315986519+streamarr-ffmpeg-lock[bot]@users.noreply.github.com`) and its historical Git-authored
email. Update these entries if the installed App identity changes so Renovate can continue
rebasing its own PR after the generated lock commit.

## CI and availability

Every CI run validates the lock offline. Live upstream metadata verification runs only when
the release, lock, resolver, or shared resolver libraries change. Ordinary read-only
`pull_request` CI tests the proposed resolver too, allowing resolver changes to be tested;
this is distinct from the privileged synchronization workflow's trusted-base boundary.
Release builds use offline metadata validation and verify the binary checksum at download time.

HLS recovery smoke tests use the same locked runtime as production on every PR. Packaging
changes additionally build and verify both native architectures, including H.264/fMP4,
AV1 encoding, and the HLS recovery tests. The required `build` status aggregates all applicable
checks.

Numbered Jellyfin releases avoid BtbN's rolling daily-build expiry. They are still upstream
assets, not a guarantee of immutable or permanent storage: a missing asset fails the build,
and a replaced asset fails checksum verification. CI caches are accelerators, not dependency
storage. A Streamarr-owned archive would be needed for rebuilds independent of upstream
retention; that is not part of this migration.
