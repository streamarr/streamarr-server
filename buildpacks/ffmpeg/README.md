# FFmpeg runtime lock

`release` is the desired BtbN release input. `ffmpeg.lock` is generated metadata for that
release and is the only source used by the buildpack when downloading FFmpeg archives.

Regenerate the lock after changing `release`:

```bash
buildpacks/ffmpeg/bin/update-lock
```

Validate the committed lock without network access:

```bash
buildpacks/ffmpeg/bin/update-lock --check
```

Verify the lock against the upstream release metadata and checksums:

```bash
buildpacks/ffmpeg/bin/update-lock --verify-upstream
```

The resolver requires Bash, `curl`, `jq`, and `sha256sum`.
