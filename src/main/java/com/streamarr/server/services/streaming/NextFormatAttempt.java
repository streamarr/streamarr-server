package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** What follows a format failure of a playback session's current format attempt. */
sealed interface NextFormatAttempt {
  /** Another format attempt encodes the video and plays this choice. */
  record Encode(@NonNull PlayingChoice choice) implements NextFormatAttempt {}

  /**
   * Another format attempt copies the video when one capability entry accepts the copy and plays
   * {@code whenCopying}. Otherwise it encodes the video and plays {@code whenEncoding}.
   */
  record CopyOrEncode(@NonNull PlayingChoice whenCopying, @NonNull PlayingChoice whenEncoding)
      implements NextFormatAttempt {}

  /**
   * Another format attempt copies the video with its Dolby Vision stripped when one capability
   * entry accepts that copy and plays {@code whenCopying}. Otherwise it encodes the video and plays
   * {@code whenEncoding}.
   */
  record CopyWithDolbyVisionStrippedOrEncode(
      @NonNull PlayingChoice whenCopying, @NonNull PlayingChoice whenEncoding)
      implements NextFormatAttempt {}

  /** The playback session ends with this failure. */
  record Fail(@NonNull PlaybackSessionFailure reason) implements NextFormatAttempt {}
}
