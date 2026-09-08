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

The updater requires Bash, `curl`, `jq`, and `sha256sum`. It accepts only published, non-prerelease tags
in `vMAJOR.MINOR.PATCH-BUILD` form, requires exactly one portable GPL asset and a GitHub
SHA-256 digest per architecture, resolves the tag's full fork commit, and writes the lock
atomically. `--check` validates the lock, notice manifest and redistribution checksums offline; `--verify-upstream` regenerates canonical
metadata from GitHub and compares it byte-for-byte without modifying the lock.

The buildpack verifies the downloaded archive against the locked checksum before extracting
its root-level `ffmpeg` and `ffprobe` binaries. Jellyfin's banner omits the packaging revision:
package `8.1.2-4` reports `8.1.2-Jellyfin`; the checksum pins the exact build. The runtime must
enable GPL, omit the `--enable-nonfree` build flag, and support `hls_segment_options`.
This flag check does not cover FDK-AAC: Jellyfin's GPL build includes stripped FDK-AAC
without setting `--enable-nonfree`. Streamarr selects FFmpeg's native `aac` encoder,
not `libfdk_aac`; the latter is nevertheless present in the distributed binary.
The layer includes GPLv3 text, bundled-library notices (including FDK's notice),
source-access instructions, and a CycloneDX SBOM.

## Redistribution materials

[`SOURCE.txt`](SOURCE.txt) supplies Corresponding Source locations, exact upstream
revisions and build/patch instructions. [`notices/sources.json`](notices/sources.json)
is the component metadata authority, including the origins/checksums of vendored
notice text inputs. The offline generator produces a consolidated notice document,
architecture-specific CycloneDX SBOMs, an input snapshot and checksums under `generated/`.
The inventory starts with each shipped Linux GPL binary's `-buildconf` output, then
traces transitive static libraries, embedded headers and generated loaders through
the pinned recipes. It is not a list of every build script or of external GPU drivers.

[`notices/manifest`](notices/manifest) binds that review to the release, source revision
and both archive digests. Offline validation and the buildpack reject a mismatch.
After an update, review both binaries and their dependencies, update the notices and
source instructions, then update the manifest. The lock resolver deliberately does
not mark new notices as reviewed. Neither does the notice generator: it never writes
`notices/manifest`. Tests check notice contents against the inventory and verify their
inclusion in fresh and cached layers. Full license texts and attribution remain in
the image, not just links to them.

With Node.js 20 or newer (developer/CI tooling only; not required by the buildpack):

```bash
# Save the previous generated/review-inputs.json before changing the reviewed inputs.
node buildpacks/ffmpeg/bin/generate-notices.mjs --compare /path/to/previous-review-inputs.json
node buildpacks/ffmpeg/bin/generate-notices.mjs
node buildpacks/ffmpeg/bin/generate-notices.mjs --check
node --test buildpacks/ffmpeg/test/generate-notices.test.mjs
```

Commit the inputs and generated outputs together. Generation makes no network calls
and does not fetch, classify or approve new dependencies. `--compare` reports changes
to component metadata (including recipes), the lock and input checksums, including
both build configurations. It does not replace reviewing actual linked dependencies
and upstream license changes. `--check` is read-only and runs with the generator
contract tests in the normal Maven build. The shell-only buildpack and offline lock
check verify generated checksums before using the materials.

`licenseExpression` records SPDX identifiers/expressions where the inventoried terms
can be represented accurately. A `LicenseRef-<component>` refers to that component's
preserved notice bundle, not a new license or a compatibility conclusion. Unique
texts (including FDK, glslang, FreeType and patent notices) are not paraphrased.
Only byte-identical texts are shared; redundant document suffixes such as `.md.txt`
are normalized in generated display names. Source-code excerpts retain `.h.txt` or
`.c.txt` to distinguish the excerpt from a compilable source file.

SBOMs include runtime and embedded inputs for their architecture; build-only inputs
are separate in `formulation`. Source revisions are recorded as provenance rather
than invented release versions. Implib's revision is explicitly notice-source-only.
The layer ships `THIRD-PARTY-NOTICES.txt`, `SOURCE.txt`, GPLv3 text, the inventory,
review manifest and both buildconf captures. FDK's standalone notices are also kept.
Other raw text inputs remain in the repository; their verbatim content is consolidated
in the installed document. The matching SBOM is contributed through the CNB SBOM path.

Source links use upstream hosting; Streamarr remains responsible for keeping the
corresponding source available with its binary distributions. Check source access
before publishing; do not publish a binary whose required source is unavailable.

## Renovate synchronization

Renovate tracks `jellyfin/jellyfin-ffmpeg` with the `github-releases` datasource and
[regex versioning](https://docs.renovatebot.com/modules/versioning/regex/). The fourth numeric
component uses the `build` capture group: `-10` sorts after `-9`, and packaging-only fixes
are stable patch updates, not SemVer prereleases. Updates have their own PR and are not
automerged. Major-version updates additionally require Dependency Dashboard approval.
Renovate still proposes eligible minor and packaging updates automatically; their
notice review must complete before CI and image packaging can pass.

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

The `SmokeTest` group (including `HlsStreamingSmokeTest`) uses the locked runtime in
the amd64 application job. Packaging changes also run that group on an arm64 host;
the packaging matrix does not repeat the amd64 host run. Separately, both native
images are built and verified in-container for runtime identity, H.264/AAC fMP4 HLS
and AV1 encoding. The required `build` status aggregates all applicable checks.

Numbered Jellyfin releases avoid BtbN's rolling daily-build expiry. They are still upstream
assets, not a guarantee of immutable or permanent storage: a missing asset fails the build,
and a replaced asset fails checksum verification. CI caches are accelerators, not dependency
storage. Jellyfin also publishes the versioned binaries at its
[FFmpeg mirror](https://repo.jellyfin.org/files/ffmpeg/linux/), using paths such as
`8.x/8.1.2-4/amd64/<asset>`. This is an available second upstream source, not yet an
automatic buildpack fallback. Neither channel provides portable-tarball signatures
or a permanent-retention promise. A Streamarr-owned archive would be needed for
rebuilds independent of both upstream channels; that is not part of this migration.
