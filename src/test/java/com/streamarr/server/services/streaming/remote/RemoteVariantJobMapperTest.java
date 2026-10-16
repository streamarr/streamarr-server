package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionPresent;
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
                    .videoSource(
                        Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
                    .build())
            .encoderPreset(EncoderPreset.FASTER)
            .build();

    var job = mapper.map(request);

    assertThat(job.getDecision().getContainer()).isEqualTo(ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
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
  @DisplayName("Should send the probe's codec string when the job copies the video")
  void shouldSendTheProbesCodecStringWhenTheJobCopiesTheVideo() {
    var video = mapper.map(copyAttempt().build()).getDecision().getVideo();

    assertThat(video.getMode()).isEqualTo(VideoMode.VIDEO_MODE_COPY);
    assertThat(video.getCodecFamily()).isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264);
    assertThat(video.getCodecString()).isEqualTo("avc1.640028");
    assertThat(video.getStreamIndex()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should send no video decision when the job copies a source that its probe gave no playback facts")
  void shouldSendNoVideoDecisionWhenTheJobCopiesASourceThatItsProbeGaveNoPlaybackFacts() {
    var request =
        copyAttempt()
            .transcodeDecision(copyDecisionBuilder().videoSource(Optional.empty()).build())
            .build();

    assertThat(mapper.map(request).getDecision().hasVideo()).isFalse();
  }

  @Test
  @DisplayName("Should force no codec profile, tier or level when the job copies the video")
  void shouldForceNoCodecProfileTierOrLevelWhenTheJobCopiesTheVideo() {
    var decision = mapper.map(copyAttempt().build()).getDecision();
    var video = decision.getVideo();

    assertThat(decision.hasVideo()).isTrue();
    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    assertThat(video.getTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
    assertThat(video.hasLevel()).isFalse();
  }

  @Test
  @DisplayName("Should refuse the job when it copies a codec family that the contract cannot name")
  void shouldRefuseTheJobWhenItCopiesACodecFamilyThatTheContractCannotName() {
    var request =
        copyAttempt()
            .transcodeDecision(copyDecisionBuilder().videoCodecFamily("vp9").build())
            .build();

    assertThatThrownBy(() -> mapper.map(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vp9");
  }

  @Test
  @DisplayName(
      "Should send the source's dynamic range and no output range when the job copies the video")
  void shouldSendTheSourcesDynamicRangeAndNoOutputRangeWhenTheJobCopiesTheVideo() {
    var request =
        copyAttempt()
            .transcodeDecision(
                copyDecisionBuilder()
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .dynamicRange(VideoPlaybackFactsFixture.dolbyVisionProfile82())
                                .codecString(Optional.of("hvc1.2.4.L120.90"))
                                .build()))
                    .videoCodecFamily("hevc")
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourceDynamicRange())
        .isEqualTo(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setDeclared(
                            DolbyVisionConfiguration.newBuilder()
                                .setProfile(8)
                                .setLevel(6)
                                .setBaseLayerCompatibilityId(2)
                                .setBaseLayer(true)
                                .setRpu(true))
                        .setObservedRpu(Presence.PRESENCE_UNSPECIFIED))
                .build());
    assertThat(video.hasOutputDynamicRange()).isFalse();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send the source's dynamic range and stream when the job encodes the video")
  void shouldSendTheSourcesDynamicRangeAndStreamWhenTheJobEncodesTheVideo() {
    var request =
        encodeAttempt("h264")
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .streamIndex(2)
                                .dynamicRange(VideoPlaybackFactsFixture.dolbyVisionProfile5())
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getStreamIndex()).isEqualTo(2);
    assertThat(video.getSourceDynamicRange())
        .isEqualTo(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setDeclared(
                            DolbyVisionConfiguration.newBuilder()
                                .setProfile(5)
                                .setLevel(6)
                                .setBaseLayerCompatibilityId(0)
                                .setBaseLayer(true)
                                .setRpu(true))
                        .setObservedRpu(Presence.PRESENCE_UNSPECIFIED))
                .build());
  }

  // ADR 0036 names each compatible base and presence value the same way in the domain and the job.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(com.streamarr.server.domain.streaming.CompatibleBase.class)
  @DisplayName("Should send the source's compatible base when a job carries it")
  void shouldSendTheSourcesCompatibleBaseWhenAJobCarriesIt(
      com.streamarr.server.domain.streaming.CompatibleBase base) {
    var range =
        sourceRangeOf(
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                base,
                com.streamarr.server.domain.streaming.Presence.UNSPECIFIED,
                new DolbyVision.Unspecified()));

    assertThat(range.getCompatibleBase().name()).isEqualTo("COMPATIBLE_BASE_" + base.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(com.streamarr.server.domain.streaming.Presence.class)
  @DisplayName("Should send the source's HDR10+ and observed RPU presence when a job carries them")
  void shouldSendTheSourcesHdr10PlusAndObservedRpuPresenceWhenAJobCarriesThem(
      com.streamarr.server.domain.streaming.Presence presence) {
    var range =
        sourceRangeOf(
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                com.streamarr.server.domain.streaming.CompatibleBase.PQ,
                presence,
                new DolbyVision.Present(Optional.empty(), presence)));

    assertThat(range.getHdr10Plus().name()).isEqualTo("PRESENCE_" + presence.name());
    assertThat(range.getDolbyVisionPresent().getObservedRpu().name())
        .isEqualTo("PRESENCE_" + presence.name());
    assertThat(range.getDolbyVisionPresent().hasDeclared()).isFalse();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private VideoDynamicRange sourceRangeOf(
      com.streamarr.server.domain.streaming.VideoDynamicRange range) {
    var request =
        copyAttempt()
            .transcodeDecision(
                copyDecisionBuilder()
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .dynamicRange(range)
                                .build()))
                    .build())
            .build();
    return mapper.map(request).getDecision().getVideo().getSourceDynamicRange();
  }

  @Test
  @DisplayName(
      "Should keep an absent and an unspecified Dolby Vision apart when a job carries them")
  void shouldKeepAnAbsentAndAnUnspecifiedDolbyVisionApartWhenAJobCarriesThem() {
    var absent =
        mapper
            .map(
                copyAttempt()
                    .transcodeDecision(
                        copyDecisionBuilder()
                            .videoSource(
                                Optional.of(
                                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                        .dynamicRange(
                                            VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION)
                                        .build()))
                            .build())
                    .build())
            .getDecision()
            .getVideo()
            .getSourceDynamicRange();
    var unspecified =
        mapper.map(copyAttempt().build()).getDecision().getVideo().getSourceDynamicRange();

    assertThat(absent.hasDolbyVisionAbsent()).isTrue();
    assertThat(unspecified.getDolbyVisionCase())
        .isEqualTo(VideoDynamicRange.DolbyVisionCase.DOLBYVISION_NOT_SET);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send the exact output frame rate when the job encodes the video")
  void shouldSendTheExactOutputFrameRateWhenTheJobEncodesTheVideo() {
    var source =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60_000, 1_001)))
            .build();
    var request =
        encodeAttempt("h264")
            .framerate(OptionalDouble.of(60_000.0 / 1_001))
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(Optional.of(source))
                    .build())
            .build();

    var execution = mapper.map(request).getExecution();

    assertThat(execution.getOutputFrameRate())
        .isEqualTo(
            com.streamarr.transcode.v1.Fraction.newBuilder()
                .setNumerator(60_000)
                .setDenominator(1_001)
                .build());
  }

  @Test
  @DisplayName("Should send no output frame rate when the job copies the video")
  void shouldSendNoOutputFrameRateWhenTheJobCopiesTheVideo() {
    var execution = mapper.map(copyAttempt().build()).getExecution();

    assertThat(execution.hasOutputFrameRate()).isFalse();
  }

  @Test
  @DisplayName(
      "Should send no output frame rate when the job encodes a source that its probe gave no playback facts")
  void shouldSendNoOutputFrameRateWhenTheJobEncodesASourceThatItsProbeGaveNoPlaybackFacts() {
    var execution = mapper.map(encodeAttempt("h264").build()).getExecution();

    assertThat(execution.hasOutputFrameRate()).isFalse();
    assertThat(execution.getFramerate()).isEqualTo(60);
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
        .transcodeDecision(copyDecisionBuilder().build())
        .encoderPreset(EncoderPreset.FASTER);
  }

  private static TranscodeDecision.TranscodeDecisionBuilder copyDecisionBuilder() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 0))
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()));
  }
}
