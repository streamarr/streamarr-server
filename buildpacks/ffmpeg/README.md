# FFmpeg runtime lock

`release` is the only Renovate-owned input. `ffmpeg.lock` is generated from that exact BtbN
release and contains the version, source revision, asset names, and SHA-256 digests consumed by the
buildpack.

Regenerate or validate the lock with:

```bash
buildpacks/ffmpeg/bin/update-lock
buildpacks/ffmpeg/bin/update-lock --release autobuild-YYYY-MM-DD-HH-MM
buildpacks/ffmpeg/bin/update-lock --check
```

The updater requires Bash, `curl`, `jq`, and `sha256sum`. It accepts only published BtbN releases,
requires exactly one GPL 8.1 Linux asset for each supported architecture, verifies GitHub's asset
digests against `checksums.sha256`, and writes the lock atomically.

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
`gitIgnoredAuthors` entry. Branch protection should require `Verify FFmpeg lock` and both
architecture image-build checks before merging FFmpeg updates.
