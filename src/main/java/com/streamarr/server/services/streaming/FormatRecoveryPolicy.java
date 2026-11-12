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
 * failure of the fixed quality's own encode ends playback. The one exception is an HDR encode whose
 * hardware encoder failed and whose software continuation could not start: SDR is better than no
 * video, so the fixed quality encodes again, and the failed HDR format stays out of that encode
 * (ADR 0013). In every playback choice, a Dolby Vision copy on a PQ or HLG base that the client
 * rejects, or whose Dolby Vision conversion the worker fails, first falls back to the copy with
 * Dolby Vision stripped, which keeps the playback choice; a worker's mismatch or a short copy would
 * repeat in that copy, so it goes straight to the encode.
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
    if (failed.output() instanceof FailedFormatAttempt.DolbyVisionCopyOnAnHdrBase
        && stripsDolbyVisionAfter(failed.reason())) {
      return withinBudget(
          new NextFormatAttempt.CopyWithDolbyVisionStrippedOrEncode(
              choiceWhenStrippingDolbyVision(choice), choiceWhenEncodingAfterACopy(choice)),
          formatAttemptCount);
    }

    return switch (choice.playbackChoice()) {
      case AUTO -> encodeWithinBudget(choice, formatAttemptCount);
      case ORIGINAL -> afterOriginalsFailure(choice, failed, formatAttemptCount);
      case LOW_360P, MEDIUM_480P, HIGH_720P, FULL_HD_1080P, UHD_4K ->
          afterAFixedQualitysFailure(choice, failed, formatAttemptCount);
    };
  }

  private static boolean stripsDolbyVisionAfter(PlaybackSessionFailure reason) {
    return switch (reason) {
      case PlaybackSessionFailure.FixedFormatFailed _,
          PlaybackSessionFailure.DolbyVisionConversionFailed _ ->
          true;
      case PlaybackSessionFailure.OutputMismatch _,
          PlaybackSessionFailure.InvalidSpecification _,
          PlaybackSessionFailure.AttemptsExhausted _,
          PlaybackSessionFailure.CreationRejected _,
          PlaybackSessionFailure.StartupFailed _ ->
          false;
    };
  }

  private NextFormatAttempt afterOriginalsFailure(
      PlayingChoice choice, FailedFormatAttempt failed, int formatAttemptCount) {
    if (failed.output() instanceof FailedFormatAttempt.DirectPlay) {
      return withinBudget(
          new NextFormatAttempt.CopyOrEncode(choice, AUTO_AFTER_A_FAILED_DIRECT_PLAY),
          formatAttemptCount);
    }

    return encodeWithinBudget(choiceWhenEncodingAfterACopy(choice), formatAttemptCount);
  }

  private NextFormatAttempt afterAFixedQualitysFailure(
      PlayingChoice choice, FailedFormatAttempt failed, int formatAttemptCount) {
    return switch (failed.output()) {
      case FailedFormatAttempt.EncodeWithoutSoftwareContinuation(var _, var format)
          when format.isHdr() ->
          encodeWithinBudget(choice, formatAttemptCount);
      case FailedFormatAttempt.Encode _, FailedFormatAttempt.EncodeWithoutSoftwareContinuation _ ->
          new NextFormatAttempt.Fail(failed.reason());
      case FailedFormatAttempt.DirectPlay _,
          FailedFormatAttempt.Copy _,
          FailedFormatAttempt.DolbyVisionCopyOnAnHdrBase _ ->
          encodeWithinBudget(choiceWhenEncodingAfterACopy(choice), formatAttemptCount);
    };
  }

  // The stripped copy keeps the source's PQ or HLG base, so Auto announces nothing. Original and a
  // fixed quality stay selected.
  private static PlayingChoice choiceWhenStrippingDolbyVision(PlayingChoice choice) {
    if (choice.playbackChoice() == VideoQuality.AUTO) {
      return choice;
    }

    return new PlayingChoice(
        choice.playbackChoice(), Optional.of(PlaybackFormatChange.DOLBY_VISION_STRIPPED));
  }

  // Original's encode plays in Auto, and a fixed quality's encode keeps the fixed quality.
  private static PlayingChoice choiceWhenEncodingAfterACopy(PlayingChoice choice) {
    return switch (choice.playbackChoice()) {
      case AUTO -> choice;
      case ORIGINAL -> AUTO_AFTER_A_FAILED_COPY;
      case LOW_360P, MEDIUM_480P, HIGH_720P, FULL_HD_1080P, UHD_4K ->
          new PlayingChoice(choice.playbackChoice(), Optional.of(PlaybackFormatChange.COPY_FAILED));
    };
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
