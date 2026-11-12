package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodeFormat;
import lombok.NonNull;

/** Why a playback session's current format attempt failed, and which output it played. */
record FailedFormatAttempt(@NonNull PlaybackSessionFailure reason, @NonNull Output output) {

  /** The output that the failed format attempt played. */
  sealed interface Output {}

  /** The format attempt sent the source file itself (ADR 0045). */
  record DirectPlay() implements Output {}

  /**
   * The format attempt copied the video without Dolby Vision that the copy with Dolby Vision
   * stripped could drop.
   */
  record Copy() implements Output {}

  /**
   * The format attempt copied the video and kept its Dolby Vision on a PQ or HLG base, which the
   * copy with Dolby Vision stripped plays (ADR 0052).
   */
  record DolbyVisionCopyOnAnHdrBase() implements Output {}

  /** The format attempt encoded the video in this codec family and format. */
  record Encode(@NonNull String codecFamily, @NonNull EncodeFormat format) implements Output {}

  /**
   * The format attempt encoded the video in this codec family and format until its hardware encoder
   * failed, and the stream session that would continue the encode in software was refused (ADR
   * 0044).
   */
  record EncodeWithoutSoftwareContinuation(
      @NonNull String codecFamily, @NonNull EncodeFormat format) implements Output {}
}
