package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.transcode.v1.ContainerFormat;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Variant Job Mapper Tests")
class RemoteVariantJobMapperTest {

  private static final Path SOURCE_ROOT = Path.of("/media/movies");

  private final RemoteVariantJobMapper mapper =
      new RemoteVariantJobMapper(SOURCE_NAMESPACE_ID, SOURCE_ROOT);

  @ParameterizedTest
  @ValueSource(strings = {"h264", "hevc", "av1"})
  @DisplayName("Should request fragmented MP4 when mapping a variant job of any video codec")
  void shouldRequestFragmentedMp4WhenMappingVariantJobOfAnyVideoCodec(String videoCodecFamily) {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
            .variantLabel(StreamSession.defaultVariant())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily(videoCodecFamily)
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .encoderPreset(EncoderPreset.FASTER)
            .build();

    var job = mapper.map(request);

    assertThat(job.getDecision().getContainer()).isEqualTo(ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should write the encoder preset when mapping a variant job")
  void shouldWriteEncoderPresetWhenMappingVariantJob() {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
            .variantLabel(StreamSession.defaultVariant())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .encoderPreset(EncoderPreset.FASTER)
            .build();

    var job = mapper.map(request);

    assertThat(job.getVariant().getEncoderPreset())
        .isEqualTo(com.streamarr.transcode.v1.EncoderPreset.ENCODER_PRESET_FASTER);
  }
}
