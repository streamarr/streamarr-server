package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Tests")
class TranscodeDecisionTest {

  private static final Fraction NTSC_FRAME_RATE = new Fraction(30_000, 1_001);

  private static final VideoPlaybackFacts FACTS_WITHOUT_CODEC_STRING =
      VideoPlaybackFactsFixture.sdrH264FactsBuilder().codecString(Optional.empty()).build();

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that copies the video when the probe stored no codec string")
  void shouldRefuseADecisionThatCopiesTheVideoWhenTheProbeStoredNoCodecString(TranscodeMode mode) {
    var decision = decisionBuilder(mode).videoSource(Optional.of(FACTS_WITHOUT_CODEC_STRING));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec string");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should accept a decision that encodes the video when the probe stored no codec string")
  void shouldAcceptADecisionThatEncodesTheVideoWhenTheProbeStoredNoCodecString(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode).videoSource(Optional.of(FACTS_WITHOUT_CODEC_STRING)).build();

    assertThat(decision.videoSource()).contains(FACTS_WITHOUT_CODEC_STRING);
  }

  @Test
  @DisplayName("Should refuse a copy when the decision holds no playback facts")
  void shouldRefuseACopyWhenTheDecisionHoldsNoPlaybackFacts() {
    var decision = decisionBuilder(TranscodeMode.REMUX).videoSource(Optional.empty());

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codec string");
  }

  @Test
  @DisplayName("Should refuse a decision when it describes no video source")
  void shouldRefuseADecisionWhenItDescribesNoVideoSource() {
    var decision = decisionBuilder(TranscodeMode.FULL_TRANSCODE);

    assertThatThrownBy(() -> decision.videoSource(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("videoSource");
  }

  @Test
  @DisplayName(
      "Should refuse a decision when its encode codec profile belongs to another codec family")
  void shouldRefuseADecisionWhenItsEncodeCodecProfileBelongsToAnotherCodecFamily() {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.AV1_MAIN));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must belong to the decision's video codec family");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that copies the video when it holds an encode codec profile")
  void shouldRefuseADecisionThatCopiesTheVideoWhenItHoldsAnEncodeCodecProfile(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode)
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot hold an encode codec profile");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modesAndTheirModeWithOtherAudio")
  @DisplayName(
      "Should keep the video path and follow the new audio when the decision takes other audio")
  void shouldKeepTheVideoPathAndFollowTheNewAudioWhenTheDecisionTakesOtherAudio(
      AudioChange change) {
    var decision =
        decisionBuilder(change.mode())
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .build();
    var audio =
        AudioDecision.stereoAac().toBuilder()
            .mode(change.audio())
            .sourceCodecString(Optional.of("mp4a.40.2"))
            .build();

    var withAudio = decision.withAudioDecision(audio);

    assertThat(withAudio.audioDecision()).isEqualTo(audio);
    assertThat(withAudio.transcodeMode()).isEqualTo(change.modeWithAudio());
    assertThat(withAudio.videoSource()).isEqualTo(decision.videoSource());
  }

  // Each video path once with audio that starts or stops passing through.
  static Stream<AudioChange> modesAndTheirModeWithOtherAudio() {
    return Stream.of(
        AudioChange.builder()
            .mode(TranscodeMode.REMUX)
            .audio(AudioMode.TRANSCODE)
            .modeWithAudio(TranscodeMode.AUDIO_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.AUDIO_TRANSCODE)
            .audio(AudioMode.COPY)
            .modeWithAudio(TranscodeMode.REMUX)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.VIDEO_TRANSCODE)
            .audio(AudioMode.TRANSCODE)
            .modeWithAudio(TranscodeMode.FULL_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.FULL_TRANSCODE)
            .audio(AudioMode.NONE)
            .modeWithAudio(TranscodeMode.VIDEO_TRANSCODE)
            .build());
  }

  @Builder
  record AudioChange(TranscodeMode mode, AudioMode audio, TranscodeMode modeWithAudio) {}

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should deinterlace one frame per field at twice the stored rate when the decision encodes an interlaced source")
  void
      shouldDeinterlaceOneFramePerFieldAtTwiceTheStoredRateWhenTheDecisionEncodesAnInterlacedSource(
          TranscodeMode mode) {
    var decision =
        decisionBuilder(mode).videoSource(Optional.of(interlacedFacts(FieldOrder.BT))).build();

    assertThat(decision.deinterlace())
        .contains(new Deinterlace(FieldOrder.BT, DeinterlaceOutput.ONE_FRAME_PER_FIELD));
    assertThat(decision.outputFrameRate()).contains(new Fraction(60_000, 1_001));
  }

  @Test
  @DisplayName("Should keep the stored rate and not deinterlace when the decision copies the video")
  void shouldKeepTheStoredRateAndNotDeinterlaceWhenTheDecisionCopiesTheVideo() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(Optional.of(interlacedFacts(FieldOrder.TT)))
            .build();

    assertThat(decision.deinterlace()).isEmpty();
    assertThat(decision.outputFrameRate()).contains(NTSC_FRAME_RATE);
  }

  @Test
  @DisplayName(
      "Should keep the stored rate and not deinterlace when the stored field order is progressive")
  void shouldKeepTheStoredRateAndNotDeinterlaceWhenTheStoredFieldOrderIsProgressive() {
    var decision =
        decisionBuilder(TranscodeMode.FULL_TRANSCODE)
            .videoSource(Optional.of(interlacedFacts(FieldOrder.PROGRESSIVE)))
            .build();

    assertThat(decision.deinterlace()).isEmpty();
    assertThat(decision.outputFrameRate()).contains(NTSC_FRAME_RATE);
  }

  @Test
  @DisplayName(
      "Should report no output frame rate when the probe stored none for an interlaced source")
  void shouldReportNoOutputFrameRateWhenTheProbeStoredNoneForAnInterlacedSource() {
    var refused =
        FrameRateRule.builder()
            .outputFrameRate(Optional.empty())
            .basis(OutputFrameRateBasis.REFUSED)
            .inputs(FrameRateRuleInputs.builder().fieldOrder(Optional.of(FieldOrder.TT)).build())
            .build();
    var decision =
        decisionBuilder(TranscodeMode.FULL_TRANSCODE)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().frameRateRule(refused).build()))
            .build();

    assertThat(decision.outputFrameRate()).isEmpty();
    assertThat(decision.deinterlace())
        .contains(new Deinterlace(FieldOrder.TT, DeinterlaceOutput.ONE_FRAME_PER_FRAME));
  }

  private static VideoPlaybackFacts interlacedFacts(FieldOrder fieldOrder) {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .frameRateRule(VideoPlaybackFactsFixture.interlacedRateRule(NTSC_FRAME_RATE, fieldOrder))
        .build();
  }

  @ParameterizedTest
  @MethodSource("compatibleBasesAndTheVideoRangesThatACopyKeeps")
  @DisplayName(
      "Should report the source's compatible base as the video range when the decision copies the"
          + " video")
  void shouldReportTheSourcesCompatibleBaseAsTheVideoRangeWhenTheDecisionCopiesTheVideo(
      VideoDynamicRange source, VideoRange expected) {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().dynamicRange(source).build()))
            .build();

    assertThat(decision.videoRange()).isEqualTo(expected);
  }

  // RFC 8216 section 4.4.6.2 names the transfer function; HLS describes Dolby Vision profile 5,
  // which has no compatible base, with PQ.
  static Stream<Arguments> compatibleBasesAndTheVideoRangesThatACopyKeeps() {
    return Stream.of(
        Arguments.of(
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.SDR), VideoRange.SDR),
        Arguments.of(
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.UNSPECIFIED),
            VideoRange.SDR),
        Arguments.of(
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ), VideoRange.PQ),
        Arguments.of(
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.HLG), VideoRange.HLG),
        Arguments.of(VideoPlaybackFactsFixture.dolbyVisionProfile82(), VideoRange.SDR),
        Arguments.of(VideoPlaybackFactsFixture.dolbyVisionProfile5(), VideoRange.PQ));
  }

  // Every encode is 8-bit SDR until the server encodes HDR (#397).
  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"SDR", "UNSPECIFIED", "PQ", "HLG"})
  @DisplayName("Should report SDR as the video range whenever the decision encodes the video")
  void shouldReportSdrAsTheVideoRangeWheneverTheDecisionEncodesTheVideo(CompatibleBase base) {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                        .build()))
            .build();

    assertThat(decision.videoRange()).isEqualTo(VideoRange.SDR);
  }

  @Test
  @DisplayName(
      "Should plan a copy with the source's range, codec profile and level and no bitstream"
          + " rewrites when the decision copies the video")
  void shouldPlanACopyWithTheSourcesRangeCodecProfileAndLevelWhenTheDecisionCopiesTheVideo() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION)
                        .videoProperties(
                            Optional.of(
                                VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()))
                        .build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isEqualTo(
            PlannedVideoOutput.StreamCopy.builder()
                .dynamicRange(VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION)
                .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
                .codecLevel(OptionalInt.of(40))
                .bitstreamRewrites(List.of())
                .build());
  }

  @Test
  @DisplayName(
      "Should plan a copy without a codec profile or level when the probe version 4 outcome"
          + " stores none")
  void shouldPlanACopyWithoutACodecProfileOrLevelWhenTheProbeVersion4OutcomeStoresNone() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION)
                        .videoProperties(Optional.empty())
                        .build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.codecProfile()).isEmpty();
              assertThat(copy.codecLevel()).isEmpty();
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceRangesAndTheRangesThatTheirCopyPlans")
  @DisplayName(
      "Should plan each value that the probe could not check as SDR or absent when the decision"
          + " copies the video")
  void shouldPlanEachValueThatTheProbeCouldNotCheckAsSdrOrAbsentWhenTheDecisionCopiesTheVideo(
      VideoDynamicRange source, VideoDynamicRange planned) {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().dynamicRange(source).build()))
            .build();

    assertThat(decision.plannedVideoOutput().dynamicRange()).isEqualTo(planned);
  }

  // ADR 0013 plans SDR for a copy of a source whose compatible base is UNSPECIFIED.
  static Stream<Arguments> sourceRangesAndTheRangesThatTheirCopyPlans() {
    var profile82 = VideoPlaybackFactsFixture.dolbyVisionProfile82();
    return Stream.of(
        Arguments.of(
            Named.of("nothing checked", VideoDynamicRange.UNSPECIFIED),
            new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent())),
        Arguments.of(
            Named.of(
                "an SDR base without HDR10+ or Dolby Vision checked",
                VideoPlaybackFactsFixture.SDR),
            new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent())),
        Arguments.of(
            Named.of("Dolby Vision profile 8.2 without HDR10+ checked", profile82),
            new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, profile82.dolbyVision())));
  }

  @Test
  @DisplayName(
      "Should plan an SDR encode in the decision's encode codec profile when the decision encodes"
          + " an HDR source")
  void shouldPlanAnSdrEncodeInTheDecisionsEncodeCodecProfileWhenTheDecisionEncodesAnHdrSource() {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(
                            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                        .build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isEqualTo(
            new PlannedVideoOutput.Encode(
                Optional.of(EncodeCodecProfile.H264_HIGH),
                VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION));
  }

  private static TranscodeDecision.TranscodeDecisionBuilder decisionBuilder(TranscodeMode mode) {
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude());
  }
}
