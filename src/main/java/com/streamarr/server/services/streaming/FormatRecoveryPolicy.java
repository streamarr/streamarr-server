package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.Optional;
import lombok.NonNull;

/**
 * Decides what follows a format failure of a playback session's current format attempt (ADR 0052).
 * Auto encodes again while its budget lasts. Original plays only copies, because an encode that it
 * reaches plays in Auto, so its failure is a failed copy: it falls back to an encode in Auto.
 */
final class FormatRecoveryPolicy {
  private static final PlayingChoice AUTO_AFTER_A_FAILED_COPY =
      new PlayingChoice(VideoQuality.AUTO, Optional.of(PlaybackFormatChange.COPY_FAILED));

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
      @NonNull PlayingChoice choice,
      @NonNull PlaybackSessionFailure failure,
      int formatAttemptCount) {
    return switch (choice.playbackChoice()) {
      case AUTO -> encodeWithinBudget(choice, formatAttemptCount);
      case ORIGINAL -> encodeWithinBudget(AUTO_AFTER_A_FAILED_COPY, formatAttemptCount);
      case LOW_360P, MEDIUM_480P, HIGH_720P, FULL_HD_1080P, UHD_4K ->
          new NextFormatAttempt.Fail(failure);
    };
  }

  private NextFormatAttempt encodeWithinBudget(PlayingChoice next, int formatAttemptCount) {
    if (formatAttemptCount >= maximumFormatAttempts) {
      return new NextFormatAttempt.Fail(new PlaybackSessionFailure.AttemptsExhausted());
    }

    return new NextFormatAttempt.Encode(next);
  }
}
