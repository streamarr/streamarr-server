#!/usr/bin/env bash
# Records the fragmented MP4 streams that the scripted FFmpeg in WorkerContainerFixture writes to
# standard output in place of a real encode. Run from the repository root:
#
#   src/test/resources/recorded-fmp4/record.sh
#
# Each stream is FFmpeg's own output from the pinned worker image (worker-image.env), made with the
# worker command recipe of ADR 0037: the mp4 muxer writes to pipe:1 with
# cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont, a 1 s fragmentation target, -copyts
# -start_at_zero, and a forced keyframe at every 6 s segment period. The source is the first 8 s of
# the committed BigBuckBunny_320x180_10s.mp4 clip, scaled down and encoded at a low bitrate so that
# each stream stays small. Only the -to that ends each run at media time 8 s is not part of the
# worker's command; under -copyts it compares against the absolute timeline.
#
# - start-0s.fmp4: media time 0 to 8 s, so its media segments are 0 and 1.
# - seek-6s.fmp4: the same encoder settings after a seek to 6 s, as a replacement attempt for
#   segment 1 runs it. Its initialization segment is byte-identical to start-0s.fmp4's.
# - seek-6s-differing-initialization.fmp4: the same seek with the baseline profile, standing in for
#   a different encoder backend. Its initialization segment differs from start-0s.fmp4's.
set -euo pipefail

cd "$(dirname "$0")"
source_clip="../BigBuckBunny_320x180_10s.mp4"
image=$(sed -n 's/^STREAMARR_WORKER_IMAGE=//p' ../../../../worker-image.env)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$source_clip" "$work/source.mp4"

record() {
  local output=$1 seek=$2 profile=$3
  local seek_args=""
  if [[ $seek != 0 ]]; then
    seek_args="-ss $seek"
  fi

  docker run --rm --entrypoint /cnb/lifecycle/launcher -v "$work:/work" "$image" bash -c "
    ffmpeg -v error -y $seek_args -i /work/source.mp4 \
      -map 0:v:0 -map 0:a:0 -map -0:s -map_metadata -1 -map_chapters -1 \
      -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128 \
      -c:v libx264 -profile:v $profile -vf scale=-2:36 -b:v 12000 -maxrate 12000 -bufsize 24000 \
      -c:a aac -ac 1 -b:a 8k \
      -r:v:0 24.0 -forced-idr 1 -force_key_frames:0 'expr:gte(t,n_forced*6)' -sc_threshold:v:0 0 \
      -g:v:0 144 \
      -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont \
      -frag_duration 1000000 -to 8 pipe:1 > /work/$output"
  cp "$work/$output" "$output"
}

record start-0s.fmp4 0 high
record seek-6s.fmp4 6 high
record seek-6s-differing-initialization.fmp4 6 baseline
