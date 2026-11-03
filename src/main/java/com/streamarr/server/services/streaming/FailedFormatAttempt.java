package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** Why a playback session's current format attempt failed, and which output it played. */
record FailedFormatAttempt(@NonNull PlaybackSessionFailure reason, @NonNull Output output) {

  /** The output that the failed format attempt played. */
  sealed interface Output {}

  /** The format attempt sent the source file itself (ADR 0045). */
  record DirectPlay() implements Output {}

  /** The format attempt copied the video. */
  record Copy() implements Output {}

  /** The format attempt encoded the video in this codec family. */
  record Encode(@NonNull String codecFamily) implements Output {}
}
