#!/usr/bin/env bash
# Records the fragmented MP4 streams that the scripted FFmpeg in WorkerContainerFixture writes to
# standard output in place of a real encode. Run from the repository root:
#
#   src/test/resources/recorded-fmp4/record.sh
#
# Each stream is FFmpeg's own output from the pinned worker image (worker-image.env), made with the
# command that the worker builds for a libx264 encode of the committed BigBuckBunny_320x180_10s.mp4
# clip (ADR 0037). The clip is 10.005 s at 24 fps, so the server advertises two 6 s media segments
# for it. The command:
# - writes fMP4 to pipe:1 with cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont and a 1 s
#   fragmentation target, under -copyts -start_at_zero;
# - encodes at the probed frame rate, -r:v:0 24.0;
# - forces keyframes at a list of whole-second media times: every 6 s boundary from the job
#   attempt's start sequence number up to, not including, the media segment count of 2;
# - sets a GOP of ceil(6 x 24) + 1 = 145 frames, the backstop for an encoder verified to honour
#   forced keyframes.
# The encoder settings scale the clip down and use a low bitrate so that each stream stays small.
# Two arguments belong to the recording, not to the worker: -v error, and the -to 8 that ends each
# stream at media time 8 s (under -copyts it compares against the absolute timeline).
#
# - start-0s.fmp4: the initial job attempt, with keyframes forced at 0 and 6 s. Its media segments
#   are 0 and 1.
# - An encoded replacement attempt for media segment 1 seeks one period early, to 0 s, so FFmpeg
#   gets no -ss, and it forces a keyframe at 6 s only. The worker discards its media segment 0 as
#   preroll. Its output is byte-identical to start-0s.fmp4, which this script checks, so the tests
#   replay start-0s.fmp4 for it.
# - replacement-differing-initialization.fmp4: that replacement attempt with -profile:v baseline,
#   standing in for a different encoder backend. Its initialization segment differs from
#   start-0s.fmp4's.
set -euo pipefail

cd "$(dirname "$0")"
source_clip="../BigBuckBunny_320x180_10s.mp4"
image=$(sed -n 's/^STREAMARR_WORKER_IMAGE=//p' ../../../../worker-image.env)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$source_clip" "$work/source.mp4"

# record OUTPUT FORCED_KEYFRAME_TIMES [ENCODER_ARGUMENT...]
record() {
  local output=$1 forced_keyframe_times=$2
  shift 2

  docker run --rm --entrypoint /cnb/lifecycle/launcher -v "$work:/work" "$image" bash -c "
    ffmpeg -v error -y -i /work/source.mp4 \
      -map 0:v:0 -map 0:a:0 -map -0:s -map_metadata -1 -map_chapters -1 \
      -copyts -avoid_negative_ts disabled -start_at_zero -max_muxing_queue_size 128 \
      -c:v libx264 $* -vf scale=-2:36 -b:v 12000 -maxrate 12000 -bufsize 24000 \
      -c:a aac -ac 1 -b:a 8k \
      -r:v:0 24.0 -forced-idr 1 -force_key_frames:0 $forced_keyframe_times -sc_threshold:v:0 0 \
      -g:v:0 145 \
      -f mp4 -movflags cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont \
      -frag_duration 1000000 -to 8 pipe:1 > /work/$output"
}

record start-0s.fmp4 0,6
record replacement.fmp4 6
record replacement-differing-initialization.fmp4 6 -profile:v baseline

if ! cmp "$work/start-0s.fmp4" "$work/replacement.fmp4"; then
  echo "The replacement attempt's output differs from start-0s.fmp4, which the tests replay for it." >&2
  exit 1
fi

cp "$work/start-0s.fmp4" "$work/replacement-differing-initialization.fmp4" .
