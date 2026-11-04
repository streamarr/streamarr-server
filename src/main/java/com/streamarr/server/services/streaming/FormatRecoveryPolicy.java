package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.Optional;
import lombok.NonNull;

/**
 * Decides what follows a format failure of a playback session's current format attempt (ADR 0052).
 * Auto encodes again while its budget lasts. Original falls back from a failed direct play to HLS
 * at original quality, which copies when it can (ADR 0045). Otherwise Original's failure is a
 * failed copy, because an encode that Original reaches plays in Auto, so it falls back to an encode
 * in Auto. A fixed quality's failed copy falls back to an encode at the same fixed quality, and the
 * failure of the fixed quality's own encode ends playback.
 */
record FormatRecoveryPolicy(int maximumFormatAttempts) {
  private static final PlayingChoice AUTO_AFTER_A_FAILED_COPY =
      new PlayingChoice(VideoQuality.AUTO, Optional.of(PlaybackFormatChange.COPY_FAILED));
  private static final PlayingChoice AUTO_AFTER_A_FAILED_DIRECT_PLAY =
      new PlayingChoice(VideoQuality.AUTO, Optional.of(PlaybackFormatChange.DIRECT_PLAY_FAILED));

  FormatRecoveryPolicy {
    if (maximumFormatAttempts <= 0) {
      throw new IllegalArgumentException("Maximum format attempts must be positive");
    }
  }

  NextFormatAttempt after(
      @NonNull PlayingChoice choice, @NonNull FailedFormatAttempt failed, int formatAttemptCount) {
    return switch (choice.playbackChoice()) {
      case AUTO -> encodeWithinBudget(choice, formatAttemptCount);
      case ORIGINAL -> afterOriginalsFailure(choice, failed, formatAttemptCount);
      case LOW_360P, MEDIUM_480P, HIGH_720P, FULL_HD_1080P, UHD_4K ->
          afterAFixedQualitysFailure(choice, failed, formatAttemptCount);
    };
  }

  private NextFormatAttempt afterOriginalsFailure(
      PlayingChoice choice, FailedFormatAttempt failed, int formatAttemptCount) {
    if (failed.output() instanceof FailedFormatAttempt.DirectPlay) {
      return withinBudget(
          new NextFormatAttempt.CopyOrEncode(choice, AUTO_AFTER_A_FAILED_DIRECT_PLAY),
          formatAttemptCount);
    }

    return encodeWithinBudget(AUTO_AFTER_A_FAILED_COPY, formatAttemptCount);
  }

  private NextFormatAttempt afterAFixedQualitysFailure(
      PlayingChoice choice, FailedFormatAttempt failed, int formatAttemptCount) {
    if (failed.output() instanceof FailedFormatAttempt.Encode) {
      return new NextFormatAttempt.Fail(failed.reason());
    }

    var encodeAtTheSameQuality =
        new PlayingChoice(choice.playbackChoice(), Optional.of(PlaybackFormatChange.COPY_FAILED));
    return encodeWithinBudget(encodeAtTheSameQuality, formatAttemptCount);
  }

  private NextFormatAttempt encodeWithinBudget(PlayingChoice next, int formatAttemptCount) {
    return withinBudget(new NextFormatAttempt.Encode(next), formatAttemptCount);
  }

  private NextFormatAttempt withinBudget(NextFormatAttempt next, int formatAttemptCount) {
    if (formatAttemptCount >= maximumFormatAttempts) {
      return new NextFormatAttempt.Fail(new PlaybackSessionFailure.AttemptsExhausted());
    }

    return next;
  }
}
