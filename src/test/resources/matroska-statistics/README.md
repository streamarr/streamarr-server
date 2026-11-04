# Matroska statistics tags

`statistics-tags.mkv` is a 4-second synthetic file whose statistics tags were written by mkvmerge.
ffprobe reports no `bit_rate` for either of its streams, so probe version 6 reads each stream's
bitrate from those tags. `ProbeVersion7WorkerIT` probes it with the pinned worker image.

The FFmpeg of the worker image that `worker-image.env` pinned at the time made the source, and Alpine's `mkvtoolnix=92.0-r0` muxed it:

```sh
docker run --rm --user 0 --entrypoint /cnb/lifecycle/launcher -v "$PWD:/work" "$STREAMARR_WORKER_IMAGE" \
  ffmpeg -nostdin -y -f lavfi -i testsrc2=size=64x36:rate=24:duration=4 \
  -f lavfi -i sine=frequency=440:duration=4 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -b:a 64k /work/src.mp4
docker run --rm -v "$PWD:/work" alpine:3.22 sh -c \
  'apk add -q mkvtoolnix=92.0-r0 && mkvmerge -q -o /work/statistics-tags.mkv /work/src.mp4'
```

mkvmerge wrote `BPS` 15596 for the video stream and 64314 for the audio stream. Both streams'
`_STATISTICS_WRITING_APP` equal the file's `WritingApp`, so the worker trusts the tags.
