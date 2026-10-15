package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Transcode Mode Tests")
class TranscodeModeTest {

  @ParameterizedTest
  @CsvSource({
    "REMUX, true",
    "AUDIO_TRANSCODE, true",
    "VIDEO_TRANSCODE, false",
    "FULL_TRANSCODE, false"
  })
  @DisplayName("Should copy the video only when the mode passes the source video through")
  void shouldCopyTheVideoOnlyWhenTheModePassesTheSourceVideoThrough(
      TranscodeMode mode, boolean copiesVideo) {
    assertThat(mode.copiesVideo()).isEqualTo(copiesVideo);
  }
}
