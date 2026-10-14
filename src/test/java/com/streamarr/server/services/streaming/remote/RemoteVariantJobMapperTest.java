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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "ULTRAFAST, ENCODER_PRESET_ULTRAFAST",
    "SUPERFAST, ENCODER_PRESET_SUPERFAST",
    "VERYFAST, ENCODER_PRESET_VERYFAST",
    "FASTER, ENCODER_PRESET_FASTER",
    "FAST, ENCODER_PRESET_FAST",
    "MEDIUM, ENCODER_PRESET_MEDIUM",
    "SLOW, ENCODER_PRESET_SLOW",
    "SLOWER, ENCODER_PRESET_SLOWER",
    "VERYSLOW, ENCODER_PRESET_VERYSLOW"
  })
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should write the encoder preset when mapping a variant job")
  void shouldWriteEncoderPresetWhenMappingVariantJob(
      EncoderPreset preset, com.streamarr.transcode.v1.EncoderPreset expected) {
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
            .encoderPreset(preset)
            .build();

    var job = mapper.map(request);

    assertThat(job.getVariant().getEncoderPreset()).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should leave align_keyframes_to_segments unset when the job copies video")
  @SuppressWarnings("deprecation")
  void shouldLeaveAlignKeyframesToSegmentsUnsetWhenJobCopiesVideo(TranscodeMode mode) {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
            .variantLabel(StreamSession.defaultVariant())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .encoderPreset(EncoderPreset.FASTER)
            .build();

    var decision = mapper.map(request).getDecision();

    assertThat(decision.getAlignKeyframesToSegments()).isFalse();
  }
}
