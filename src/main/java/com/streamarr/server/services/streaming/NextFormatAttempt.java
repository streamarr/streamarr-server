package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** What follows a format failure of a playback session's current format attempt. */
sealed interface NextFormatAttempt {
  /** Another format attempt encodes the video. */
  record Encode() implements NextFormatAttempt {}

  /** The playback session ends with this failure. */
  record Fail(@NonNull PlaybackSessionFailure reason) implements NextFormatAttempt {}
}
