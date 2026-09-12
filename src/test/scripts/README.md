# Smoke prerequisite regression test

With Java 25, Python 3, and the repository's Node.js toolchain selected, run:

```sh
python3 src/test/scripts/test_hls_smoke_prerequisites.py
```

This POSIX-only harness launches the existing HLS smoke test through Maven with an
isolated PATH. It verifies that the suite skips before setup when neither FFmpeg
tool is available, or when `ffmpeg` is available but `ffprobe` is missing. A small
executable fixture models a successful `ffmpeg` availability check; the harness
does not test successful transcoding.

Run it separately from other Maven commands because it uses the same build output.
Logs and JUnit reports are saved under `target/review-repros/`. This harness is not
included in the normal Maven test lifecycle.
