package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoQuality;
import lombok.NonNull;

/** Decides what follows a format failure of a playback session's current format attempt. */
final class FormatRecoveryPolicy {
  private final int maximumFormatAttempts;

  FormatRecoveryPolicy(int maximumFormatAttempts) {
    if (maximumFormatAttempts <= 0) {
      throw new IllegalArgumentException("Maximum format attempts must be positive");
    }

    this.maximumFormatAttempts = maximumFormatAttempts;
  }

  int maximumFormatAttempts() {
    return maximumFormatAttempts;
  }

  NextFormatAttempt after(
      @NonNull VideoQuality playbackChoice,
      @NonNull PlaybackSessionFailure failure,
      int formatAttemptCount) {
    if (playbackChoice != VideoQuality.AUTO) {
      return new NextFormatAttempt.Fail(failure);
    }

    if (formatAttemptCount >= maximumFormatAttempts) {
      return new NextFormatAttempt.Fail(new PlaybackSessionFailure.AttemptsExhausted());
    }

    return new NextFormatAttempt.Encode();
  }
}
