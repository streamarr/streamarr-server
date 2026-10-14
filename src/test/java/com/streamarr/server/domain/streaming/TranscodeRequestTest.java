package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Request Tests")
class TranscodeRequestTest {

  @Test
  @DisplayName("Should reject construction when the encoder preset is missing")
  void shouldRejectConstructionWhenTheEncoderPresetIsMissing() {
    var builder =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movies/movie.mkv"))
            .variantLabel(StreamSession.defaultVariant())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build());

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("encoderPreset");
  }
}
