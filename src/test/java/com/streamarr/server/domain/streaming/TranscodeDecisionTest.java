package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

  private static final AudioDecision COPIED_STEREO_AAC =
      AudioDecision.builder()
          .mode(AudioMode.COPY)
          .codec("aac")
          .channels(2)
          .bitrate(128_000L)
          .sourceCodecString(Optional.of("mp4a.40.2"))
          .build();

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

  // The builder defaults the entry refusals, the audio stream index and the HDR choice, so only the
  // canonical constructor can receive null for them.
  @ParameterizedTest(name = "{0}")
  @MethodSource("decisionsConstructedWithoutAValue")
  @DisplayName("Should refuse a decision when its constructor receives no value for a component")
  void shouldRefuseADecisionWhenItsConstructorReceivesNoValueForAComponent(
      String missing, ThrowingCallable construct) {
    assertThatThrownBy(construct)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(missing);
  }

  static Stream<Arguments> decisionsConstructedWithoutAValue() {
    var audio = AudioDecision.stereoAac();
    var subtitles = SubtitleDecision.exclude();
    return Stream.of(
        Arguments.of(
            "videoCapabilityEntryRefusals",
            (ThrowingCallable)
                () ->
                    new TranscodeDecision(
                        TranscodeMode.FULL_TRANSCODE,
                        "h264",
                        Optional.empty(),
                        audio,
                        subtitles,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        null,
                        OptionalInt.empty(),
                        Optional.empty(),
                        false,
                        EncodeFormat.SDR_8_BIT,
                        Optional.empty())),
        Arguments.of(
            "audioStreamIndex",
            (ThrowingCallable)
                () ->
                    new TranscodeDecision(
                        TranscodeMode.FULL_TRANSCODE,
                        "h264",
                        Optional.empty(),
                        audio,
                        subtitles,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        null,
                        Optional.empty(),
                        false,
                        EncodeFormat.SDR_8_BIT,
                        Optional.empty())),
        Arguments.of(
            "dynamicRangeChoice",
            (ThrowingCallable)
                () ->
                    new TranscodeDecision(
                        TranscodeMode.FULL_TRANSCODE,
                        "h264",
                        Optional.empty(),
                        audio,
                        subtitles,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        OptionalInt.empty(),
                        null,
                        false,
                        EncodeFormat.SDR_8_BIT,
                        Optional.empty())),
        Arguments.of(
            "lowerVariantEncode",
            (ThrowingCallable)
                () ->
                    new TranscodeDecision(
                        TranscodeMode.FULL_TRANSCODE,
                        "h264",
                        Optional.empty(),
                        audio,
                        subtitles,
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        false,
                        EncodeFormat.SDR_8_BIT,
                        null)));
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

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that encodes the video when it holds a lower variant encode")
  void shouldRefuseADecisionThatEncodesTheVideoWhenItHoldsALowerVariantEncode(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode)
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .lowerVariantEncode(
                Optional.of(
                    new LowerVariantEncode(EncodeCodecProfile.H264_HIGH, EncodeFormat.SDR_8_BIT)));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot hold a lower variant encode");
  }

  // ADR 0050: a bandwidth dip must not move a television between HDR and SDR, and a 10-bit copy
  // keeps its 10 bits, so no decision pairs a copy with lower variants in another format.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesAndLowerVariantsInAnotherFormat")
  @DisplayName(
      "Should refuse a copy decision when its lower variants leave the copy's range or bit depth")
  void shouldRefuseACopyDecisionWhenItsLowerVariantsLeaveTheCopysRangeOrBitDepth(
      VideoPlaybackFacts copied, LowerVariantEncode lowerVariants) {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(Optional.of(copied))
            .lowerVariantEncode(Optional.of(lowerVariants));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must keep the copy's range and bit depth");
  }

  static Stream<Arguments> copiesAndLowerVariantsInAnotherFormat() {
    var eightBitSdr = new LowerVariantEncode(EncodeCodecProfile.H264_HIGH, EncodeFormat.SDR_8_BIT);
    var pq = new LowerVariantEncode(EncodeCodecProfile.AV1_MAIN, EncodeFormat.hdr(VideoRange.PQ));
    return Stream.of(
        Arguments.of(
            Named.of("a PQ copy above 8-bit SDR", tenBitFacts(CompatibleBase.PQ)), eightBitSdr),
        Arguments.of(
            Named.of("an HLG copy above 8-bit SDR", tenBitFacts(CompatibleBase.HLG)), eightBitSdr),
        Arguments.of(
            Named.of("a 10-bit SDR copy above 8-bit SDR", tenBitFacts(CompatibleBase.SDR)),
            eightBitSdr),
        Arguments.of(Named.of("an HLG copy above PQ", tenBitFacts(CompatibleBase.HLG)), pq),
        Arguments.of(
            Named.of(
                "an 8-bit SDR copy above PQ",
                VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()),
            pq),
        Arguments.of(
            Named.of(
                "a 12-bit PQ copy above 10-bit PQ",
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .dynamicRange(
                        VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                    .bitDepth(OptionalInt.of(12))
                    .build()),
            pq));
  }

  // A lower variant beside a PQ copy is a 10-bit PQ encode of the same video stream with the
  // source's static metadata, and it keeps neither the copy's rewrites nor its Dolby Vision.
  @Test
  @DisplayName(
      "Should plan each lower variant as an encode in the lower variant encode's codec profile and"
          + " format when a copy holds one")
  void shouldPlanEachLowerVariantAsAnEncodeInItsCodecProfileAndFormatWhenACopyHoldsOne() {
    var copied =
        tenBitFactsBuilder(CompatibleBase.PQ)
            .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
            .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
            .build();
    var copy =
        decisionBuilder(TranscodeMode.REMUX)
            .videoCodecFamily("hevc")
            .videoSource(Optional.of(copied))
            .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_HDR10_PLUS))
            .lowerVariantEncode(
                Optional.of(
                    new LowerVariantEncode(
                        EncodeCodecProfile.HEVC_MAIN_10, EncodeFormat.hdr(VideoRange.PQ))))
            .build();

    var lowerVariant = copy.lowerVariantDecision().orElseThrow();

    assertThat(lowerVariant.transcodeMode().copiesVideo()).isFalse();
    assertThat(lowerVariant.videoCodecFamily()).isEqualTo("hevc");
    assertThat(lowerVariant.videoBitstreamRewrites()).isEmpty();
    assertThat(lowerVariant.lowerVariantEncode()).isEmpty();
    assertThat(lowerVariant.videoRange()).isEqualTo(copy.videoRange());
    assertThat(lowerVariant.plannedVideoOutput())
        .isEqualTo(
            PlannedVideoOutput.Encode.builder()
                .codecProfile(Optional.of(EncodeCodecProfile.HEVC_MAIN_10))
                .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
                .bitDepth(10)
                .chromaSubsampling(ChromaSubsampling.CHROMA_420)
                .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
                .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
                .build());
  }

  // 10-bit HEVC Main 10 in the compatible base, with neither HDR10+ nor Dolby Vision.
  private static VideoPlaybackFacts tenBitFacts(CompatibleBase base) {
    return tenBitFactsBuilder(base).build();
  }

  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder tenBitFactsBuilder(
      CompatibleBase base) {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
        .codecString(Optional.of("hvc1.2.4.L150.90"))
        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
        .bitDepth(OptionalInt.of(10));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modesAndTheirModeWithOtherAudio")
  @DisplayName(
      "Should keep the video path and follow the new audio when the decision takes other audio")
  void shouldKeepTheVideoPathAndFollowTheNewAudioWhenTheDecisionTakesOtherAudio(
      AudioChange change) {
    var decision =
        decisionBuilder(change.mode())
            .audioDecision(change.startingAudio())
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .build();
    var audio = change.audio();

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
            .startingAudio(COPIED_STEREO_AAC)
            .audio(AudioDecision.stereoAac())
            .modeWithAudio(TranscodeMode.AUDIO_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.AUDIO_TRANSCODE)
            .startingAudio(AudioDecision.stereoAac())
            .audio(COPIED_STEREO_AAC)
            .modeWithAudio(TranscodeMode.REMUX)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.VIDEO_TRANSCODE)
            .startingAudio(COPIED_STEREO_AAC)
            .audio(AudioDecision.stereoAac())
            .modeWithAudio(TranscodeMode.FULL_TRANSCODE)
            .build(),
        AudioChange.builder()
            .mode(TranscodeMode.FULL_TRANSCODE)
            .startingAudio(AudioDecision.stereoAac())
            .audio(AudioDecision.none())
            .modeWithAudio(TranscodeMode.VIDEO_TRANSCODE)
            .build());
  }

  @Builder
  record AudioChange(
      TranscodeMode mode,
      AudioDecision startingAudio,
      AudioDecision audio,
      TranscodeMode modeWithAudio) {}

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
      "Should plan a copy with the source's range, profile and level and no rewrites when the"
          + " decision copies the video")
  void
      shouldPlanACopyWithTheSourcesRangeProfileAndLevelAndNoRewritesWhenTheDecisionCopiesTheVideo() {
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
                .bitDepth(OptionalInt.of(8))
                .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
                .bitstreamRewrites(List.of())
                .build());
  }

  @Test
  @DisplayName(
      "Should plan the source's bit depth and chroma subsampling when the decision copies a 10-bit"
          + " 4:2:2 stream")
  void shouldPlanTheSourcesBitDepthAndChromaSubsamplingWhenTheDecisionCopiesA10Bit422Stream() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .bitDepth(OptionalInt.of(10))
                        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_422))
                        .build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.bitDepth()).hasValue(10);
              assertThat(copy.chromaSubsampling()).contains(ChromaSubsampling.CHROMA_422);
            });
  }

  // Every encode is 8-bit 4:2:0 until the server chooses 10-bit and HDR encodes (#397).
  @Test
  @DisplayName("Should plan an 8-bit 4:2:0 encode when the decision encodes a 10-bit 4:2:2 source")
  void shouldPlanAn8Bit420EncodeWhenTheDecisionEncodesA10Bit422Source() {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .bitDepth(OptionalInt.of(10))
                        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_422))
                        .build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.Encode.class,
            encode -> {
              assertThat(encode.bitDepth()).isEqualTo(8);
              assertThat(encode.chromaSubsampling()).isEqualTo(ChromaSubsampling.CHROMA_420);
            });
  }

  @Test
  @DisplayName("Should refuse a decision that copies the video when it holds a 10-bit HDR format")
  void shouldRefuseADecisionThatCopiesTheVideoWhenItHoldsA10BitHdrFormat() {
    var copy =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .videoEncodeFormat(EncodeFormat.hdr(VideoRange.PQ));

    assertThatThrownBy(copy::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("encode format");
  }

  @ParameterizedTest
  @EnumSource(DynamicRangeChoice.class)
  @DisplayName("Should carry the viewer's HDR choice when the decision names one")
  void shouldCarryTheViewersHdrChoiceWhenTheDecisionNamesOne(DynamicRangeChoice choice) {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .dynamicRangeChoice(Optional.of(choice))
            .build();

    assertThat(decision.dynamicRangeChoice()).contains(choice);
  }

  @Test
  @DisplayName("Should carry no HDR choice when the decision names none")
  void shouldCarryNoHdrChoiceWhenTheDecisionNamesNone() {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .build();

    assertThat(decision.dynamicRangeChoice()).isEmpty();
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
            Named.of(
                "Dolby Vision profile 8.2 whose HDR10+ and RPUs the probe did not read", profile82),
            new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, profile82.dolbyVision())));
  }

  // ADR 0036: a stream copy passes HDR10+ and the Dolby Vision RPUs through unchanged.
  @Test
  @DisplayName(
      "Should keep the source's HDR10+ and Dolby Vision facts in the planned copy when the probe"
          + " found both")
  void shouldKeepTheSourcesHdr10PlusAndDolbyVisionFactsInThePlannedCopyWhenTheProbeFoundBoth() {
    var source =
        new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, dolbyVisionProfile81Observed());
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().dynamicRange(source).build()))
            .build();

    assertThat(decision.plannedVideoOutput().dynamicRange()).isEqualTo(source);
  }

  // ADR 0036: a copy that strips HDR10+ plans no HDR10+, and the playlist describes the output
  // after its rewrites.
  @Test
  @DisplayName(
      "Should plan HDR10+ as absent and list the rewrite when the decision copies an HDR10+ source"
          + " with HDR10+ stripped")
  void
      shouldPlanHdr10PlusAsAbsentAndListTheRewriteWhenTheDecisionCopiesAnHdr10PlusSourceWithHdr10PlusStripped() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(
                            new VideoDynamicRange(
                                CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent()))
                        .build()))
            .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.dynamicRange())
                  .isEqualTo(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ));
              assertThat(copy.bitstreamRewrites())
                  .containsExactly(BitstreamRewrite.STRIP_HDR10_PLUS);
            });
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should refuse a decision that encodes the video when it lists a bitstream rewrite")
  void shouldRefuseADecisionThatEncodesTheVideoWhenItListsABitstreamRewrite(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_HDR10_PLUS));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bitstream rewrite");
  }

  // ADR 0036: the worker writes each static HDR value that the probe holds into an HEVC copy with
  // a PQ output, and never invents one.
  @Test
  @DisplayName("Should plan the source's static HDR metadata when the decision copies a PQ source")
  void shouldPlanTheSourcesStaticHdrMetadataWhenTheDecisionCopiesAPqSource() {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(Optional.of(hdrFactsWithStaticMetadata(CompatibleBase.PQ)))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.masteringDisplay())
                  .contains(VideoPlaybackFactsFixture.MASTERING_DISPLAY);
              assertThat(copy.contentLightLevel())
                  .contains(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL);
            });
  }

  @ParameterizedTest
  @EnumSource(
      value = CompatibleBase.class,
      names = {"HLG", "SDR"})
  @DisplayName("Should plan no static HDR metadata when the copy's output is not PQ")
  void shouldPlanNoStaticHdrMetadataWhenTheCopysOutputIsNotPq(CompatibleBase base) {
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(Optional.of(hdrFactsWithStaticMetadata(base)))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.masteringDisplay()).isEmpty();
              assertThat(copy.contentLightLevel()).isEmpty();
            });
  }

  private static VideoPlaybackFacts hdrFactsWithStaticMetadata(CompatibleBase base) {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
        .masteringDisplay(Optional.of(VideoPlaybackFactsFixture.MASTERING_DISPLAY))
        .contentLightLevel(Optional.of(VideoPlaybackFactsFixture.CONTENT_LIGHT_LEVEL))
        .build();
  }

  // ADR 0013: the playlist describes a copy that keeps Dolby Vision on a PQ base as Dolby Vision,
  // at the level that the decision set.
  @Test
  @DisplayName(
      "Should plan the Dolby Vision output when the decision copies a source that it describes as"
          + " Dolby Vision")
  void shouldPlanTheDolbyVisionOutputWhenTheDecisionCopiesASourceThatItDescribesAsDolbyVision() {
    var output = new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6);
    var decision =
        decisionBuilder(TranscodeMode.REMUX)
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .dynamicRange(
                            new VideoDynamicRange(
                                CompatibleBase.PQ, Presence.ABSENT, dolbyVisionProfile81Observed()))
                        .build()))
            .videoBitstreamRewrites(List.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .videoDolbyVisionOutput(Optional.of(output))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> assertThat(copy.dolbyVisionOutput()).contains(output));
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should refuse a decision that encodes the video when it holds a Dolby Vision output")
  void shouldRefuseADecisionThatEncodesTheVideoWhenItHoldsADolbyVisionOutput(TranscodeMode mode) {
    var decision =
        decisionBuilder(mode)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoDolbyVisionOutput(
                Optional.of(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, 6)));

    assertThatThrownBy(decision::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dolby Vision output");
  }

  // Profile 8.1 as probe version 5 reports it after reading the RPUs: a record, an observed first
  // RPU header and an RPU after its own picture.
  private static DolbyVision.Present dolbyVisionProfile81Observed() {
    return new DolbyVision.Present(
        Optional.of(
            DolbyVisionConfiguration.builder()
                .profile(OptionalInt.of(8))
                .level(OptionalInt.of(6))
                .baseLayerCompatibilityId(OptionalInt.of(1))
                .baseLayer(true)
                .rpu(true)
                .build()),
        Presence.PRESENT,
        Optional.of(
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .disableResidual(true)
                .vdrBitDepth(10)
                .build()),
        DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hdrSourceRanges")
  @DisplayName(
      "Should plan an SDR encode in the decision's encode codec profile when the decision encodes"
          + " an HDR source")
  void shouldPlanAnSdrEncodeInTheDecisionsEncodeCodecProfileWhenTheDecisionEncodesAnHdrSource(
      VideoDynamicRange source) {
    var decision =
        decisionBuilder(TranscodeMode.VIDEO_TRANSCODE)
            .encodeCodecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
            .videoSource(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().dynamicRange(source).build()))
            .build();

    assertThat(decision.plannedVideoOutput())
        .isEqualTo(
            PlannedVideoOutput.Encode.builder()
                .codecProfile(Optional.of(EncodeCodecProfile.H264_HIGH))
                .dynamicRange(VideoPlaybackFactsFixture.SDR_WITHOUT_DOLBY_VISION)
                .bitDepth(8)
                .chromaSubsampling(ChromaSubsampling.CHROMA_420)
                .build());
  }

  // ADR 0013: every encode plans an SDR base without HDR10+ or Dolby Vision, whatever the source.
  static Stream<Named<VideoDynamicRange>> hdrSourceRanges() {
    return Stream.of(
        Named.of(
            "a PQ source without HDR10+ or Dolby Vision",
            VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ)),
        Named.of(
            "a PQ source with HDR10+",
            new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent())),
        Named.of(
            "a Dolby Vision profile 5 source", VideoPlaybackFactsFixture.dolbyVisionProfile5()));
  }

  private static TranscodeDecision.TranscodeDecisionBuilder decisionBuilder(TranscodeMode mode) {
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude());
  }
}
