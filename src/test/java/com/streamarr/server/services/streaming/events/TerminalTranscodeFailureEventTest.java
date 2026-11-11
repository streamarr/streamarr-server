package com.streamarr.server.services.streaming.events;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Terminal Transcode Failure Event Tests")
class TerminalTranscodeFailureEventTest {

  @Test
  @DisplayName(
      "Should refuse the event when it does not say whether the failed job copied the video")
  void shouldRefuseTheEventWhenItDoesNotSayWhetherTheFailedJobCopiedTheVideo() {
    var event =
        TerminalTranscodeFailureEvent.builder()
            .streamSessionId(UUID.randomUUID())
            .jobAttemptId(UUID.randomUUID())
            .variantLabel(StreamSession.defaultVariant())
            .failure(TranscodeFailure.COPIED_AUDIO_RAN_SHORT);

    assertThatNullPointerException().isThrownBy(event::build).withMessageContaining("copiedVideo");
  }
}
