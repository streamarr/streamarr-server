package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.VideoQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
