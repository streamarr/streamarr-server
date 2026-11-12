package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Format Recovery Policy Tests")
class FormatRecoveryPolicyTest {
  private final FormatRecoveryPolicy policy = new FormatRecoveryPolicy(4);

  @Test
  @DisplayName("Should reject a missing playing choice when deciding the next format attempt")
  void shouldRejectAMissingPlayingChoiceWhenDecidingTheNextFormatAttempt() {
    var failed =
        new FailedFormatAttempt(
            new PlaybackSessionFailure.OutputMismatch(), new FailedFormatAttempt.Copy());

    assertThatNullPointerException().isThrownBy(() -> policy.after(null, failed, 1));
  }

  @Test
  @DisplayName("Should reject a missing failed attempt when deciding the next format attempt")
  void shouldRejectAMissingFailedAttemptWhenDecidingTheNextFormatAttempt() {
    var choice = PlayingChoice.requested(VideoQuality.HIGH_720P);

    assertThatNullPointerException().isThrownBy(() -> policy.after(choice, null, 1));
  }

  // SDR is better than no video, so a fixed quality gives the HDR range up when no stream session
  // can continue its encode in software in that range (ADR 0013).
  @ParameterizedTest
  @MethodSource("hdrFormats")
  @DisplayName(
      "Should encode the fixed quality again when no stream session can continue its HDR encode"
          + " in software")
  void shouldEncodeTheFixedQualityAgainWhenNoStreamSessionCanContinueItsHdrEncodeInSoftware(
      EncodeFormat format) {
    var choice = PlayingChoice.requested(VideoQuality.HIGH_720P);

    var next = policy.after(choice, continuationRejected(format), 1);

    assertThat(next).isEqualTo(new NextFormatAttempt.Encode(choice));
  }

  static Stream<EncodeFormat> hdrFormats() {
    return Stream.of(EncodeFormat.hdr(VideoRange.PQ), EncodeFormat.hdr(VideoRange.HLG));
  }

  @Test
  @DisplayName(
      "Should end a fixed quality's playback when no stream session can continue its 10-bit SDR"
          + " encode in software")
  void shouldEndAFixedQualitysPlaybackWhenNoStreamSessionCanContinueIts10BitSdrEncode() {
    var failed = continuationRejected(EncodeFormat.SDR_10_BIT);

    var next = policy.after(PlayingChoice.requested(VideoQuality.HIGH_720P), failed, 1);

    assertThat(next).isEqualTo(new NextFormatAttempt.Fail(failed.reason()));
  }

  // Only an encode that no software encoder could continue encodes again, whatever the reason of
  // the failure says.
  @ParameterizedTest(name = "{0}")
  @MethodSource("failuresOfAnEncode")
  @DisplayName("Should end a fixed quality's playback when its PQ encode fails")
  void shouldEndAFixedQualitysPlaybackWhenItsPqEncodeFails(PlaybackSessionFailure reason) {
    var failed =
        new FailedFormatAttempt(
            reason, new FailedFormatAttempt.Encode("av1", EncodeFormat.hdr(VideoRange.PQ)));

    var next = policy.after(PlayingChoice.requested(VideoQuality.HIGH_720P), failed, 1);

    assertThat(next).isEqualTo(new NextFormatAttempt.Fail(reason));
  }

  static Stream<Named<PlaybackSessionFailure>> failuresOfAnEncode() {
    return Stream.of(
        Named.of(
            "the player rejects its format",
            new PlaybackSessionFailure.FixedFormatFailed(PlaybackFormatFailure.DECODE_ERROR)),
        Named.of(
            "a stream session is refused",
            new PlaybackSessionFailure.CreationRejected(
                new CreateStreamSessionRejection.NoEligibleVideoFormat())));
  }

  @Test
  @DisplayName(
      "Should end a fixed quality's playback for its spent budget when no stream session can"
          + " continue its HDR encode in software")
  void
      shouldEndAFixedQualitysPlaybackForItsSpentBudgetWhenNoStreamSessionCanContinueItsHdrEncode() {
    var failed = continuationRejected(EncodeFormat.hdr(VideoRange.PQ));

    var next = policy.after(PlayingChoice.requested(VideoQuality.HIGH_720P), failed, 4);

    assertThat(next)
        .isEqualTo(new NextFormatAttempt.Fail(new PlaybackSessionFailure.AttemptsExhausted()));
  }

  private static FailedFormatAttempt continuationRejected(EncodeFormat format) {
    return new FailedFormatAttempt(
        new PlaybackSessionFailure.CreationRejected(
            new CreateStreamSessionRejection.NoEligibleVideoFormat()),
        new FailedFormatAttempt.EncodeWithoutSoftwareContinuation("av1", format));
  }
}
