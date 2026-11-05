package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.transcode.v1.AudioChannelLayout;
import com.streamarr.transcode.v1.BitstreamRewrite;
import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionOutput;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.DolbyVisionSampleEntry;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCodecProfile;
import com.streamarr.transcode.v1.VideoCodecTier;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .frameRateRule(
                                    VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                                .build()))
                    .build())
            .encoderPreset(preset)
            .build();

    var job = mapper.map(request);

    assertThat(job.getVariant().getEncoderPreset()).isEqualTo(expected);
  }

  // ADR 0050: the job names the output layout of every audio encode, and the worker encodes with
  // FFmpeg's -ch_layout.
  @ParameterizedTest(name = "{0}")
  @MethodSource("outputLayoutsAndTheirContractValues")
  @DisplayName("Should name the encode's output layout when the job encodes the audio")
  void shouldNameTheEncodesOutputLayoutWhenTheJobEncodesTheAudio(OutputLayout outputLayout) {
    var layout = outputLayout.layout();
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("eac3")
            .channels(layout.getChannels())
            .bitrate(768_000L)
            .channelLayout(Optional.of(layout))
            .build();
    var encode = encodeAttempt("h264").build();
    var request =
        encodeAttempt("h264")
            .transcodeDecision(encode.transcodeDecision().withAudioDecision(audio))
            .build();

    var job = mapper.map(request);

    assertThat(job.getDecision().getAudio().getChannelLayout())
        .isEqualTo(outputLayout.contractLayout());
  }

  static Stream<OutputLayout> outputLayoutsAndTheirContractValues() {
    return Stream.of(
        new OutputLayout(ChannelLayout.MONO, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_MONO),
        new OutputLayout(ChannelLayout.STEREO, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO),
        new OutputLayout(
            ChannelLayout.TWO_POINT_ONE, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE),
        new OutputLayout(
            ChannelLayout.THREE_POINT_ZERO,
            AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO),
        new OutputLayout(
            ChannelLayout.FOUR_POINT_ZERO, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO),
        new OutputLayout(ChannelLayout.QUAD, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD),
        new OutputLayout(
            ChannelLayout.QUAD_SIDE, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD_SIDE),
        new OutputLayout(
            ChannelLayout.FIVE_POINT_ZERO, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO),
        new OutputLayout(
            ChannelLayout.FIVE_POINT_ZERO_SIDE,
            AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE),
        new OutputLayout(
            ChannelLayout.FIVE_POINT_ONE, AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE),
        new OutputLayout(
            ChannelLayout.FIVE_POINT_ONE_SIDE,
            AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE));
  }

  record OutputLayout(ChannelLayout layout, AudioChannelLayout contractLayout) {}

  // A copy keeps the source's own layout, so the job names none.
  @Test
  @DisplayName("Should name no output layout when the job copies the audio")
  void shouldNameNoOutputLayoutWhenTheJobCopiesTheAudio() {
    var job = mapper.map(copyAttempt().build());

    assertThat(job.getDecision().getAudio().getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_UNSPECIFIED);
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
    var source =
        VideoPlaybackFacts.builder()
            .streamIndex(0)
            .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
            .build();
    var request =
        encodeAttempt("h264")
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(mode)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.none())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(Optional.of(source))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.hasSourceDynamicRange()).isTrue();
    assertThat(video.getSourceDynamicRange()).isEqualTo(VideoDynamicRange.getDefaultInstance());
    assertThat(video.getSourceCodecFamily())
        .isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED);
    assertThat(video.getOutputDynamicRange())
        .isEqualTo(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                .build());
    assertThat(video.hasStreamIndex()).isTrue();
    assertThat(video.getStreamIndex()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "H264, VIDEO_CODEC_FAMILY_H264",
    "HEVC, VIDEO_CODEC_FAMILY_HEVC",
    "AV1, VIDEO_CODEC_FAMILY_AV1",
    "OTHER, VIDEO_CODEC_FAMILY_UNSPECIFIED"
  })
  @DisplayName("Should send the source's codec family when the job encodes the video")
  void shouldSendTheSourcesCodecFamilyWhenTheJobEncodesTheVideo(
      ProbedCodecFamily probedCodecFamily, VideoCodecFamily sourceCodecFamily) {
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
                                .codecFamily(Optional.of(probedCodecFamily))
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourceCodecFamily()).isEqualTo(sourceCodecFamily);
    assertThat(video.getCodecFamily()).isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264);
  }

  @ParameterizedTest
  @CsvSource({
    "h264, H264, VIDEO_CODEC_FAMILY_H264",
    "hevc, HEVC, VIDEO_CODEC_FAMILY_HEVC",
    "av1, AV1, VIDEO_CODEC_FAMILY_AV1"
  })
  @DisplayName("Should send the source's codec family when the job copies the video")
  void shouldSendTheSourcesCodecFamilyWhenTheJobCopiesTheVideo(
      String copiedCodec, ProbedCodecFamily probedCodecFamily, VideoCodecFamily sourceCodecFamily) {
    var request =
        copyAttempt()
            .transcodeDecision(
                copyDecisionBuilder()
                    .videoCodecFamily(copiedCodec)
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .codecFamily(Optional.of(probedCodecFamily))
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourceCodecFamily()).isEqualTo(sourceCodecFamily);
  }

  @Test
  @DisplayName("Should send no source codec family when the probe stored no codec")
  void shouldSendNoSourceCodecFamilyWhenTheProbeStoredNoCodec() {
    var request =
        encodeAttempt("av1")
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("av1")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .codecFamily(Optional.empty())
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourceCodecFamily())
        .isEqualTo(VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("storedPresentationTimes")
  @DisplayName(
      "Should tell an encode whether the source's packets carry presentation times when the probe"
          + " stored the fact")
  void shouldTellAnEncodeWhetherTheSourcesPacketsCarryPresentationTimesWhenTheProbeStoredTheFact(
      String stored, Optional<VideoStreamProperties> properties, boolean carried) {
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
                                .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
                                .videoProperties(properties)
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourcePacketsCarryPresentationTimes()).isEqualTo(carried);
  }

  static Stream<Arguments> storedPresentationTimes() {
    return Stream.of(
        Arguments.of("stored true", propertiesWithPresentationTimes(Optional.of(true)), true),
        Arguments.of("stored false", propertiesWithPresentationTimes(Optional.of(false)), false),
        Arguments.of("stored no value", propertiesWithPresentationTimes(Optional.empty()), false),
        Arguments.of("probed before version 5", Optional.empty(), false));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should tell a copy whether the source's packets carry presentation times when the probe"
          + " stored the fact")
  void shouldTellACopyWhetherTheSourcesPacketsCarryPresentationTimesWhenTheProbeStoredTheFact(
      boolean carried) {
    var request =
        copyAttempt()
            .transcodeDecision(
                copyDecisionBuilder()
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .videoProperties(
                                    propertiesWithPresentationTimes(Optional.of(carried)))
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getSourcePacketsCarryPresentationTimes()).isEqualTo(carried);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("storedCopyPacketBytes")
  @DisplayName(
      "Should tell a copy to keep every packet's bytes only when the probe stored that it can")
  void shouldTellACopyToKeepEveryPacketsBytesOnlyWhenTheProbeStoredThatItCan(
      String stored, Optional<VideoStreamProperties> properties, boolean kept) {
    var request =
        copyAttempt()
            .transcodeDecision(
                copyDecisionBuilder()
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .videoProperties(properties)
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getCopyKeepsPacketBytes()).isEqualTo(kept);
  }

  static Stream<Arguments> storedCopyPacketBytes() {
    return Stream.of(
        Arguments.of("stored true", propertiesWithCopyPacketBytes(Optional.of(true)), true),
        Arguments.of("stored false", propertiesWithCopyPacketBytes(Optional.of(false)), false),
        Arguments.of("stored no value", propertiesWithCopyPacketBytes(Optional.empty()), false),
        Arguments.of("probed before version 5", Optional.empty(), false));
  }

  @Test
  @DisplayName(
      "Should not tell an encode to keep every packet's bytes when the probe stored that a copy"
          + " can")
  void shouldNotTellAnEncodeToKeepEveryPacketsBytesWhenTheProbeStoredThatACopyCan() {
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
                                .videoProperties(propertiesWithCopyPacketBytes(Optional.of(true)))
                                .build()))
                    .build())
            .build();

    var video = mapper.map(request).getDecision().getVideo();

    assertThat(video.getCopyKeepsPacketBytes()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should omit the retired codec family string when mapping a video decision")
  void shouldOmitTheRetiredCodecFamilyStringWhenMappingAVideoDecision(boolean encodeVideo) {
    var request = encodeVideo ? encodeAttempt("av1") : copyAttempt();
    var decision = mapper.map(request.build()).getDecision();

    assertThat(decision.getVideoCodecFamily()).isEmpty();
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

  // ADR 0013's third amendment: a copy plans the source's compatible base, and a planned output
  // holds no UNSPECIFIED value (ADR 0036). A worker that lists the planned output feature accepts
  // the range on a copy.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWithAPlannedRange")
  @DisplayName(
      "Should send the copy's planned range when its output is PQ, HLG or has no compatible base")
  void shouldSendTheCopysPlannedRangeWhenItsOutputIsPqHlgOrHasNoCompatibleBase(
      String source,
      com.streamarr.server.domain.streaming.VideoDynamicRange stored,
      VideoDynamicRange planned) {
    var video = mapper.map(copyOf(stored)).getDecision().getVideo();

    assertThat(video.getOutputDynamicRange()).isEqualTo(planned);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> copiesWithAPlannedRange() {
    return Stream.of(
        Arguments.of(
            "an HDR10+ source with an unchecked Dolby Vision",
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                com.streamarr.server.domain.streaming.CompatibleBase.PQ,
                com.streamarr.server.domain.streaming.Presence.PRESENT,
                new DolbyVision.Unspecified()),
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setHdr10Plus(Presence.PRESENCE_PRESENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                .build()),
        Arguments.of(
            "an HLG source",
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(
                com.streamarr.server.domain.streaming.CompatibleBase.HLG),
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_HLG)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                .build()),
        Arguments.of(
            "a Dolby Vision profile 5 source with an unchecked HDR10+",
            VideoPlaybackFactsFixture.dolbyVisionProfile5(),
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
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
                .build()));
  }

  // An SDR copy without rewrites passes the source's range through, so it still reaches a worker
  // that predates the planned output. An untagged source plays as SDR (ADR 0013).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.server.domain.streaming.CompatibleBase.class,
      names = {"SDR", "UNSPECIFIED"})
  @DisplayName("Should send no output range when the copy passes an SDR or untagged source through")
  void shouldSendNoOutputRangeWhenTheCopyPassesAnSdrOrUntaggedSourceThrough(
      com.streamarr.server.domain.streaming.CompatibleBase base) {
    var video =
        mapper
            .map(copyOf(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base)))
            .getDecision()
            .getVideo();

    assertThat(video.hasOutputDynamicRange()).isFalse();
  }

  // ADR 0036: the worker writes each static HDR value that the probe holds into a PQ copy's hvcC
  // record, in the unit of its SEI field.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send the probed static HDR values when the copy's output is PQ")
  void shouldSendTheProbedStaticHdrValuesWhenTheCopysOutputIsPq() {
    var video =
        mapper
            .map(copyWithStaticHdrValues(com.streamarr.server.domain.streaming.CompatibleBase.PQ))
            .getDecision()
            .getVideo();

    assertThat(video.getMasteringDisplay())
        .isEqualTo(
            MasteringDisplayColorVolume.newBuilder()
                .setRed(ChromaticityCoordinates.newBuilder().setX(34_000).setY(16_000))
                .setGreen(ChromaticityCoordinates.newBuilder().setX(13_250).setY(34_500))
                .setBlue(ChromaticityCoordinates.newBuilder().setX(7_500).setY(3_000))
                .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(15_635).setY(16_450))
                .setMaxLuminance(10_000_000)
                .setMinLuminance(50)
                .build());
    assertThat(video.getContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1_000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send no static HDR value when the copy's output is HLG")
  void shouldSendNoStaticHdrValueWhenTheCopysOutputIsHlg() {
    var video =
        mapper
            .map(copyWithStaticHdrValues(com.streamarr.server.domain.streaming.CompatibleBase.HLG))
            .getDecision()
            .getVideo();

    assertThat(video.hasMasteringDisplay()).isFalse();
    assertThat(video.hasContentLightLevel()).isFalse();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send no static HDR value when the probe stored none for a PQ copy")
  void shouldSendNoStaticHdrValueWhenTheProbeStoredNoneForAPqCopy() {
    var video =
        mapper
            .map(
                copyOf(
                    VideoPlaybackFactsFixture.rangeWithoutEnhancement(
                        com.streamarr.server.domain.streaming.CompatibleBase.PQ)))
            .getDecision()
            .getVideo();

    assertThat(video.hasMasteringDisplay()).isFalse();
    assertThat(video.hasContentLightLevel()).isFalse();
  }

  // ADR 0036: the job lists every rewrite of a copy, and the planned range describes the output
  // after them.
  @Test
  @DisplayName(
      "Should send the rewrite and a range without HDR10+ when the copy strips HDR10+ from its"
          + " source")
  void shouldSendTheRewriteAndARangeWithoutHdr10PlusWhenTheCopyStripsHdr10PlusFromItsSource() {
    var video = mapper.map(hdr10PlusCopyWithHdr10PlusStripped()).getDecision().getVideo();

    assertThat(video.getBitstreamRewritesList())
        .containsExactly(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS);
    assertThat(video.getOutputDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
  }

  // Slot counts and execution targets read this projection, so it carries every field that decides
  // which workers can run the job.
  @Test
  @DisplayName("Should project the copy's rewrites when the copy strips HDR10+ from its source")
  void shouldProjectTheCopysRewritesWhenTheCopyStripsHdr10PlusFromItsSource() {
    var video =
        RemoteVariantJobMapper.decision(hdr10PlusCopyWithHdr10PlusStripped().transcodeDecision())
            .getVideo();

    assertThat(video.getBitstreamRewritesList())
        .containsExactly(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_HDR10_PLUS);
    assertThat(video.hasOutputDynamicRange()).isTrue();
  }

  // ADR 0013: the worker writes the record that the playlist describes, under the sample entry that
  // the playlist's CODECS names.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionOutputs")
  @DisplayName("Should send the Dolby Vision output when the copy keeps Dolby Vision")
  void shouldSendTheDolbyVisionOutputWhenTheCopyKeepsDolbyVision(
      DolbyVisionProfile profile, DolbyVisionOutput expected) {
    var video = mapper.map(copyKeepingDolbyVision(profile, 7)).getDecision().getVideo();

    assertThat(video.getDolbyVisionOutput()).isEqualTo(expected);
  }

  static Stream<Arguments> dolbyVisionOutputs() {
    return Stream.of(
        Arguments.of(
            DolbyVisionProfile.PROFILE_5,
            dolbyVisionOutput(5, 0, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DVH1)),
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_1,
            dolbyVisionOutput(8, 1, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1)),
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_4,
            dolbyVisionOutput(8, 4, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1)),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_0,
            dolbyVisionOutput(10, 0, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DAV1)),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_1,
            dolbyVisionOutput(10, 1, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_AV01)),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_4,
            dolbyVisionOutput(10, 4, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_AV01)));
  }

  private static DolbyVisionOutput dolbyVisionOutput(
      int profile, int compatibilityId, DolbyVisionSampleEntry sampleEntry) {
    return DolbyVisionOutput.newBuilder()
        .setProfile(profile)
        .setLevel(7)
        .setBaseLayerCompatibilityId(compatibilityId)
        .setSampleEntry(sampleEntry)
        .build();
  }

  // ADR 0013: the worker checks the produced initialization segment against the job's codec string.
  // A copy without a compatible base names its dvh1 or dav1 sample entry with the Dolby Vision
  // profile and level, and a copy on a PQ or HLG base keeps the codec string that the probe stored.
  @ParameterizedTest
  @CsvSource({"PROFILE_5, dvh1.05.07", "PROFILE_10_0, dav1.10.07", "PROFILE_8_1, avc1.640028"})
  @DisplayName(
      "Should send the codec string of the copy's sample entry when the copy keeps Dolby Vision")
  void shouldSendTheCodecStringOfTheCopysSampleEntryWhenTheCopyKeepsDolbyVision(
      DolbyVisionProfile profile, String codecString) {
    var video = mapper.map(copyKeepingDolbyVision(profile, 7)).getDecision().getVideo();

    assertThat(video.getCodecString()).isEqualTo(codecString);
  }

  // Slot counts and execution targets read the Dolby Vision output to find the workers that write
  // its sample entry.
  @Test
  @DisplayName("Should project the Dolby Vision output when the copy keeps Dolby Vision")
  void shouldProjectTheDolbyVisionOutputWhenTheCopyKeepsDolbyVision() {
    var video =
        RemoteVariantJobMapper.decision(
                copyKeepingDolbyVision(DolbyVisionProfile.PROFILE_8_1, 7).transcodeDecision())
            .getVideo();

    assertThat(video.getDolbyVisionOutput())
        .isEqualTo(dolbyVisionOutput(8, 1, DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should send no Dolby Vision output when the copy strips Dolby Vision")
  void shouldSendNoDolbyVisionOutputWhenTheCopyStripsDolbyVision() {
    var video =
        mapper
            .map(
                copyAttempt()
                    .transcodeDecision(
                        copyDecisionBuilder()
                            .videoSource(Optional.of(dolbyVisionFacts()))
                            .videoBitstreamRewrites(
                                List.of(
                                    com.streamarr.server.domain.streaming.BitstreamRewrite
                                        .STRIP_DOLBY_VISION))
                            .build())
                    .build())
            .getDecision()
            .getVideo();

    assertThat(video.hasDolbyVisionOutput()).isFalse();
    assertThat(video.getBitstreamRewritesList())
        .containsExactly(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static TranscodeRequest copyKeepingDolbyVision(DolbyVisionProfile profile, int level) {
    return copyAttempt()
        .transcodeDecision(
            copyDecisionBuilder()
                .videoSource(Optional.of(dolbyVisionFacts()))
                .videoBitstreamRewrites(
                    List.of(
                        com.streamarr.server.domain.streaming.BitstreamRewrite
                            .NORMALIZE_DOLBY_VISION_RECORD))
                .videoDolbyVisionOutput(
                    Optional.of(
                        new com.streamarr.server.domain.streaming.DolbyVisionOutput(
                            profile, level)))
                .build())
        .build();
  }

  // The playlist describes the copy from the decision's Dolby Vision output, so the probed facts
  // need only show that Dolby Vision is present.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoPlaybackFacts dolbyVisionFacts() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .dynamicRange(
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                com.streamarr.server.domain.streaming.CompatibleBase.PQ,
                com.streamarr.server.domain.streaming.Presence.ABSENT,
                new DolbyVision.Present(
                    Optional.empty(), com.streamarr.server.domain.streaming.Presence.PRESENT)))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static TranscodeRequest hdr10PlusCopyWithHdr10PlusStripped() {
    return copyAttempt()
        .transcodeDecision(
            copyDecisionBuilder()
                .videoSource(
                    Optional.of(
                        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                            .dynamicRange(
                                new com.streamarr.server.domain.streaming.VideoDynamicRange(
                                    com.streamarr.server.domain.streaming.CompatibleBase.PQ,
                                    com.streamarr.server.domain.streaming.Presence.PRESENT,
                                    new DolbyVision.Absent()))
                            .build()))
                .videoBitstreamRewrites(
                    List.of(
                        com.streamarr.server.domain.streaming.BitstreamRewrite.STRIP_HDR10_PLUS))
                .build())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static TranscodeRequest copyWithStaticHdrValues(
      com.streamarr.server.domain.streaming.CompatibleBase base) {
    return copyAttempt()
        .transcodeDecision(
            copyDecisionBuilder()
                .videoSource(
                    Optional.of(
                        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                            .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                            .masteringDisplay(
                                Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
                            .contentLightLevel(
                                Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
                            .build()))
                .build())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static TranscodeRequest copyOf(
      com.streamarr.server.domain.streaming.VideoDynamicRange stored) {
    return copyAttempt()
        .transcodeDecision(
            copyDecisionBuilder()
                .videoSource(
                    Optional.of(
                        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                            .dynamicRange(stored)
                            .build()))
                .build())
        .build();
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

  // Each case sets every field apart from its default, and from the fields that could take its
  // place, so a field that the mapper drops or swaps reaches the worker wrong.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @MethodSource("firstRpuHeaders")
  @DisplayName(
      "Should send the source's first RPU header and RPU placement when the probe read them")
  void shouldSendTheSourcesFirstRpuHeaderAndRpuPlacementWhenTheProbeReadThem(
      DolbyVisionRpuHeader stored, com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder sent) {
    var range =
        sourceRangeOf(
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                com.streamarr.server.domain.streaming.CompatibleBase.NONE,
                com.streamarr.server.domain.streaming.Presence.ABSENT,
                new com.streamarr.server.domain.streaming.DolbyVision.Present(
                    Optional.empty(),
                    com.streamarr.server.domain.streaming.Presence.PRESENT,
                    Optional.of(stored),
                    DolbyVisionRpuPlacement.STARTS_SAMPLE)));

    assertThat(range.getDolbyVisionPresent())
        .isEqualTo(
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(Presence.PRESENCE_PRESENT)
                .setFirstRpuHeader(sent)
                .setRpuPlacement(
                    com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                        .DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE)
                .build());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> firstRpuHeaders() {
    return Stream.of(
        Arguments.of(
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .baseLayerFullRange(true)
                .enhancementLayerSpatialResamplingFilter(false)
                .disableResidual(true)
                .vdrBitDepth(12)
                .build(),
            com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setBaseLayerFullRange(true)
                .setDisableResidual(true)
                .setVdrBitDepth(12)),
        Arguments.of(
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .baseLayerFullRange(false)
                .enhancementLayerSpatialResamplingFilter(true)
                .disableResidual(true)
                .vdrBitDepth(10)
                .build(),
            com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setEnhancementLayerSpatialResamplingFilter(true)
                .setDisableResidual(true)
                .setVdrBitDepth(10)));
  }

  // ADR 0036 names each RPU placement the same way in the domain and the job.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(DolbyVisionRpuPlacement.class)
  @DisplayName("Should send the source's RPU placement when a job carries it")
  void shouldSendTheSourcesRpuPlacementWhenAJobCarriesIt(DolbyVisionRpuPlacement placement) {
    var range =
        sourceRangeOf(
            new com.streamarr.server.domain.streaming.VideoDynamicRange(
                com.streamarr.server.domain.streaming.CompatibleBase.NONE,
                com.streamarr.server.domain.streaming.Presence.ABSENT,
                new com.streamarr.server.domain.streaming.DolbyVision.Present(
                    Optional.empty(),
                    com.streamarr.server.domain.streaming.Presence.PRESENT,
                    Optional.empty(),
                    placement)));

    assertThat(range.getDolbyVisionPresent().getRpuPlacement().name())
        .isEqualTo("DOLBY_VISION_RPU_PLACEMENT_" + placement.name());
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
                new DolbyVision.Present(
                    Optional.empty(), com.streamarr.server.domain.streaming.Presence.UNSPECIFIED)));

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

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should send the variant's divided frame rate when the variant encodes below the source's"
          + " output frame rate")
  void shouldSendTheVariantsDividedFrameRateWhenTheVariantEncodesBelowTheSourcesOutputFrameRate() {
    var source =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60_000, 1_001)))
            .build();
    var request =
        encodeAttempt("h264")
            .outputFrameRate(Optional.of(new Fraction(30_000, 1_001)))
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
                .setNumerator(30_000)
                .setDenominator(1_001)
                .build());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @CsvSource({
    "TT, FIELD_ORDER_TT",
    "BB, FIELD_ORDER_BB",
    "TB, FIELD_ORDER_TB",
    "BT, FIELD_ORDER_BT"
  })
  @DisplayName(
      "Should tell the worker to deinterlace one frame per field at twice the stored rate when the job encodes an interlaced source")
  void
      shouldTellTheWorkerToDeinterlaceOneFramePerFieldAtTwiceTheStoredRateWhenTheJobEncodesAnInterlacedSource(
          FieldOrder fieldOrder, com.streamarr.transcode.v1.FieldOrder expectedFieldOrder) {
    var job = mapper.map(interlacedEncodeAttempt(fieldOrder).build());

    var deinterlace = job.getDecision().getVideo().getDeinterlace();
    assertThat(deinterlace.getFieldOrder()).isEqualTo(expectedFieldOrder);
    assertThat(deinterlace.getOutput())
        .isEqualTo(
            com.streamarr.transcode.v1.DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD);
    assertThat(job.getExecution().getOutputFrameRate())
        .isEqualTo(
            com.streamarr.transcode.v1.Fraction.newBuilder()
                .setNumerator(60_000)
                .setDenominator(1_001)
                .build());
  }

  // Twice 50 frames per second exceeds the 60 that Apple's HLS Authoring Specification item 1.19
  // allows, so the worker writes one frame per source frame at the stored rate.
  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName(
      "Should tell the worker to deinterlace one frame per source frame at the stored rate when twice the stored rate exceeds 60")
  void
      shouldTellTheWorkerToDeinterlaceOneFramePerSourceFrameAtTheStoredRateWhenTwiceTheStoredRateExceeds60() {
    var job = mapper.map(interlacedEncodeAttempt(FieldOrder.BB, new Fraction(50, 1)).build());

    assertThat(job.getDecision().getVideo().getDeinterlace().getOutput())
        .isEqualTo(
            com.streamarr.transcode.v1.DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FRAME);
    assertThat(job.getExecution().getOutputFrameRate())
        .isEqualTo(
            com.streamarr.transcode.v1.Fraction.newBuilder()
                .setNumerator(50)
                .setDenominator(1)
                .build());
  }

  // H.264 level 4.0 allows 1080p only up to 30 frames per second; level 4.2 allows 60 (ITU-T H.264
  // table A-1), so the encode of 1080i at one frame per field forces level 4.2.
  @Test
  @DisplayName(
      "Should force the level that allows the field rate when the job deinterlaces 1080i one frame per field")
  void shouldForceTheLevelThatAllowsTheFieldRateWhenTheJobDeinterlaces1080iOneFramePerField() {
    var video = mapper.map(interlacedEncodeAttempt(FieldOrder.TT).build()).getDecision().getVideo();

    assertThat(video.getLevel()).isEqualTo(42);
    assertThat(video.getCodecString()).isEqualTo("avc1.64002A");
  }

  @Test
  @DisplayName("Should send no deinterlace instruction when the job encodes a progressive source")
  void shouldSendNoDeinterlaceInstructionWhenTheJobEncodesAProgressiveSource() {
    var video = mapper.map(encodeAttempt("h264").build()).getDecision().getVideo();

    assertThat(video.hasDeinterlace()).isFalse();
  }

  @Test
  @DisplayName("Should send no output frame rate when the job copies the video")
  void shouldSendNoOutputFrameRateWhenTheJobCopiesTheVideo() {
    var execution = mapper.map(copyAttempt().build()).getExecution();

    assertThat(execution.hasOutputFrameRate()).isFalse();
  }

  @Test
  @DisplayName("Should refuse to map an encode when the source has no playback facts")
  void shouldRefuseToMapAnEncodeWhenTheSourceHasNoPlaybackFacts() {
    var request =
        encodeAttempt("h264")
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .build())
            .build();

    assertThatThrownBy(() -> mapper.map(request))
        .isInstanceOf(UnsupportedVideoEncodeException.class)
        .hasMessageContaining("frame rate");
  }

  private static Optional<VideoStreamProperties> propertiesWithPresentationTimes(
      Optional<Boolean> carried) {
    return Optional.of(
        VideoStreamProperties.builder()
            .firstFramesRead(true)
            .firstPacketsCarryPresentationTimes(carried)
            .build());
  }

  private static Optional<VideoStreamProperties> propertiesWithCopyPacketBytes(
      Optional<Boolean> kept) {
    return Optional.of(VideoStreamProperties.builder().copyKeepsPacketBytes(kept).build());
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
                .videoSource(
                    Optional.of(
                        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                            .frameRateRule(
                                VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                            .build()))
                .build())
        .encoderPreset(EncoderPreset.FASTER);
  }

  // 1080i at 29.97 frames, 59.94 fields, per second.
  private static TranscodeRequest.TranscodeRequestBuilder interlacedEncodeAttempt(
      FieldOrder fieldOrder) {
    return interlacedEncodeAttempt(fieldOrder, new Fraction(30_000, 1_001));
  }

  private static TranscodeRequest.TranscodeRequestBuilder interlacedEncodeAttempt(
      FieldOrder fieldOrder, Fraction storedOutputFrameRate) {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .videoCodecFamily("h264")
            .audioDecision(AudioDecision.stereoAac())
            .subtitleDecision(SubtitleDecision.exclude())
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .frameRateRule(
                            VideoPlaybackFactsFixture.interlacedRateRule(
                                storedOutputFrameRate, fieldOrder))
                        .build()))
            .build();
    return encodeAttempt("h264").bitrate(8_000_000L).transcodeDecision(decision);
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
        .audioDecision(
            AudioDecision.builder()
                .mode(AudioMode.COPY)
                .codec("aac")
                .channels(2)
                .bitrate(0)
                .sourceCodecString(Optional.of("mp4a.40.2"))
                .build())
        .subtitleDecision(SubtitleDecision.exclude())
        .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()));
  }
}
