# FFmpeg runtime lock

`release` is the only Renovate-owned input. `ffmpeg.lock` is generated from that exact BtbN
release and contains the version, full FFmpeg source commit, selected GPL asset variant, asset
names, and SHA-256 digests consumed by the buildpack.

Regenerate or validate the lock with:

```bash
buildpacks/ffmpeg/bin/update-lock
buildpacks/ffmpeg/bin/update-lock --release autobuild-YYYY-MM-DD-HH-MM
buildpacks/ffmpeg/bin/update-lock --check
buildpacks/ffmpeg/bin/update-lock --verify-upstream
```

The updater requires Bash, `curl`, `jq`, and `sha256sum`. It accepts only published BtbN releases,
requires exactly one GPL 8.1 Linux asset for each supported architecture, verifies GitHub's asset
digests against `checksums.sha256`, resolves the full FFmpeg source commit, and writes the lock
atomically. `--check` validates the lock offline; `--verify-upstream` regenerates canonical metadata
from GitHub and compares it byte-for-byte without modifying the lock.

## Renovate synchronization

`.github/workflows/sync-ffmpeg-lock.yml` runs trusted resolver code from the PR base against the
Renovate head checkout. It reads the proposed release directly from the PR Git object as data,
generates the lock entirely in the trusted checkout, stages only that generated blob, checks that
the head SHA has not moved, and then pushes it with a short-lived GitHub App token.

Install a GitHub App on this repository with repository contents read/write access and configure
these Actions secrets:

- `FFMPEG_LOCK_APP_CLIENT_ID`
- `FFMPEG_LOCK_APP_PRIVATE_KEY`

The workflow authors commits as
`streamarr-ffmpeg-lock[bot]@users.noreply.github.com`; this must remain identical to Renovate's
`gitIgnoredAuthors` entry. The required `build` status aggregates application tests, offline lock
verification, and both architecture image builds whenever packaging inputs change.
