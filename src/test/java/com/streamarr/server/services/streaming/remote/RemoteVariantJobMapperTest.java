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
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCodecProfile;
import com.streamarr.transcode.v1.VideoCodecTier;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
                    .transcodeMode(TranscodeMode.REMUX)
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
            .framerate(OptionalDouble.of(24))
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

  @Test
  @DisplayName("Should write the keyframe seek position when a copy attempt carries one")
  void shouldWriteTheKeyframeSeekPositionWhenACopyAttemptCarriesOne() {
    var request =
        copyAttempt().keyframeSeekPosition(Optional.of(Duration.ofMillis(600_558))).build();

    var execution = mapper.map(request).getExecution();

    assertThat(execution.hasSeekPosition()).isTrue();
    assertThat(execution.getSeekPosition().getSeconds()).isEqualTo(600);
    assertThat(execution.getSeekPosition().getNanos()).isEqualTo(558_000_000);
  }

  @Test
  @DisplayName("Should leave the seek position unset when an attempt carries no keyframe seek")
  void shouldLeaveTheSeekPositionUnsetWhenAnAttemptCarriesNoKeyframeSeek() {
    var execution = mapper.map(copyAttempt().build()).getExecution();

    assertThat(execution.hasSeekPosition()).isFalse();
  }

  @Test
  @DisplayName("Should send the forced H.264 values when the job encodes H.264")
  void shouldSendTheForcedH264ValuesWhenTheJobEncodesH264() {
    var video = mapper.map(encodeAttempt("h264").build()).getDecision().getVideo();

    assertThat(video.getMode()).isEqualTo(VideoMode.VIDEO_MODE_ENCODE);
    assertThat(video.getCodecFamily()).isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264);
    assertThat(video.getCodecProfile()).isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH);
    assertThat(video.getTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
    assertThat(video.hasLevel()).isTrue();
    assertThat(video.getLevel()).isEqualTo(42);
    assertThat(video.getCodecString()).isEqualTo("avc1.64002A");
  }

  @Test
  @DisplayName("Should send the forced AV1 values in the Main tier when the job encodes AV1")
  void shouldSendTheForcedAv1ValuesInTheMainTierWhenTheJobEncodesAv1() {
    var video = mapper.map(encodeAttempt("av1").build()).getDecision().getVideo();

    assertThat(video.getMode()).isEqualTo(VideoMode.VIDEO_MODE_ENCODE);
    assertThat(video.getCodecFamily()).isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1);
    assertThat(video.getCodecProfile()).isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN);
    assertThat(video.getTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_MAIN);
    assertThat(video.getLevel()).isEqualTo(41);
    assertThat(video.getCodecString()).isEqualTo("av01.0.09M.08");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should plan SDR output from an unspecified source when the job encodes video")
  void shouldPlanSdrOutputFromAnUnspecifiedSourceWhenTheJobEncodesVideo(TranscodeMode mode) {
    var request =
        encodeAttempt("h264")
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.none())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.hasSourceDynamicRange()).isTrue();
    assertThat(video.getSourceDynamicRange()).isEqualTo(VideoDynamicRange.getDefaultInstance());
    assertThat(video.getOutputDynamicRange())
        .isEqualTo(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                .build());
    assertThat(video.hasStreamIndex()).isFalse();
  }

  @Test
  @DisplayName("Should keep the codec family name beside the video decision when the job encodes")
  void shouldKeepTheCodecFamilyNameBesideTheVideoDecisionWhenTheJobEncodes() {
    var decision = mapper.map(encodeAttempt("av1").build()).getDecision();

    assertThat(decision.getVideoCodecFamily()).isEqualTo("av1");
    assertThat(decision.hasVideo()).isTrue();
  }

  @Test
  @DisplayName("Should send no video decision when the job copies the video")
  void shouldSendNoVideoDecisionWhenTheJobCopiesTheVideo() {
    var decision = mapper.map(copyAttempt().build()).getDecision();

    assertThat(decision.hasVideo()).isFalse();
  }

  private static TranscodeRequest.TranscodeRequestBuilder encodeAttempt(String videoCodecFamily) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
        .variantLabel("1080p")
        .framerate(OptionalDouble.of(60))
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily(videoCodecFamily)
                .audioDecision(AudioDecision.stereoAac())
                .subtitleDecision(SubtitleDecision.exclude())
                .build())
        .encoderPreset(EncoderPreset.FASTER);
  }

  private static TranscodeRequest.TranscodeRequestBuilder copyAttempt() {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
        .variantLabel(StreamSession.defaultVariant())
        .seekPosition(600)
        .startSequenceNumber(100)
        .mediaSegmentCount(1_200)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.copy("aac", 2, 0))
                .subtitleDecision(SubtitleDecision.exclude())
                .build())
        .encoderPreset(EncoderPreset.FASTER);
  }
}
