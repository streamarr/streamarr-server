package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("videoPathsAndAudioModes")
  @DisplayName("Should encode the audio in the mode only when the audio does not pass through")
  void shouldEncodeTheAudioInTheModeOnlyWhenTheAudioDoesNotPassThrough(ModeOfParts parts) {
    assertThat(TranscodeMode.of(parts.copiesVideo(), parts.audio())).isEqualTo(parts.mode());
  }

  // Copied audio and no audio pass through; an audio encode does not.
  static Stream<ModeOfParts> videoPathsAndAudioModes() {
    var videoCopy = ModeOfParts.builder().copiesVideo(true);
    var videoEncode = ModeOfParts.builder().copiesVideo(false);
    return Stream.of(
        videoCopy.audio(AudioMode.COPY).mode(TranscodeMode.REMUX).build(),
        videoCopy.audio(AudioMode.NONE).mode(TranscodeMode.REMUX).build(),
        videoCopy.audio(AudioMode.TRANSCODE).mode(TranscodeMode.AUDIO_TRANSCODE).build(),
        videoEncode.audio(AudioMode.COPY).mode(TranscodeMode.VIDEO_TRANSCODE).build(),
        videoEncode.audio(AudioMode.NONE).mode(TranscodeMode.VIDEO_TRANSCODE).build(),
        videoEncode.audio(AudioMode.TRANSCODE).mode(TranscodeMode.FULL_TRANSCODE).build());
  }

  @Builder
  record ModeOfParts(boolean copiesVideo, AudioMode audio, TranscodeMode mode) {}
}
