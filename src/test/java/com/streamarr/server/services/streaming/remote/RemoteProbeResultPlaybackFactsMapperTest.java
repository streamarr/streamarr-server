package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.FrameRatePacketScan;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MatroskaStatistics;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.FrameRateRuleResult;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Playback Facts Mapper Tests")
class RemoteProbeResultPlaybackFactsMapperTest {

  private static final int PROBE_VERSION_4 = 4;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Nested
  @DisplayName("Stream Facts")
  class StreamFacts {

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @Test
    @DisplayName("Should keep each video fact when a probe version 4 result reports them")
    void shouldKeepEachVideoFactWhenAProbeVersion4ResultReportsThem() {
      var hdrRecording =
          ProbeStreamInfo.newBuilder()
              .setIndex(0)
              .setCodecType("video")
              .setCodec("hevc")
              .setBitDepth(10)
              .setChromaSubsampling(
                  com.streamarr.transcode.v1.ChromaSubsampling.CHROMA_SUBSAMPLING_420)
              .setRotationDegrees(-90)
              .setContainerCropping(
                  com.streamarr.transcode.v1.ContainerCropping.newBuilder()
                      .setBottom(8)
                      .setLeft(2)
                      .setRight(4))
              .setCodecString("hvc1.2.4.L123.B0");
      hdrRecording
          .getDynamicRangeBuilder()
          .setCompatibleBase(com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_PQ);

      var stream = streamsOf(result(media -> media.setStreams(0, hdrRecording))).getFirst();

      assertThat(stream)
          .isEqualTo(
              StreamInfo.builder()
                  .index(0)
                  .codecType("video")
                  .codec(Optional.of("hevc"))
                  .dynamicRange(
                      Optional.of(
                          new VideoDynamicRange(
                              CompatibleBase.PQ,
                              Presence.UNSPECIFIED,
                              new DolbyVision.Unspecified())))
                  .bitDepth(OptionalInt.of(10))
                  .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
                  .rotationDegrees(OptionalInt.of(-90))
                  .containerCropping(
                      Optional.of(
                          ContainerCropping.builder().top(0).bottom(8).left(2).right(4).build()))
                  .codecString(Optional.of("hvc1.2.4.L123.B0"))
                  .build());
    }

    @Test
    @DisplayName("Should keep each audio fact when a probe version 4 result reports them")
    void shouldKeepEachAudioFactWhenAProbeVersion4ResultReportsThem() {
      var atmos =
          ProbeStreamInfo.newBuilder()
              .setIndex(1)
              .setCodecType("audio")
              .setCodec("eac3")
              .setCodecString("ec-3")
              .setSampleRateHz(48_000)
              .setAudioProfile("Dolby Digital Plus + Dolby Atmos");

      var stream = streamsOf(result(media -> media.addStreams(atmos))).get(1);

      assertThat(stream)
          .isEqualTo(
              StreamInfo.builder()
                  .index(1)
                  .codecType("audio")
                  .codec(Optional.of("eac3"))
                  .codecString(Optional.of("ec-3"))
                  .sampleRate(OptionalInt.of(48_000))
                  .audioProfile(Optional.of("Dolby Digital Plus + Dolby Atmos"))
                  .build());
    }

    @ParameterizedTest
    @ValueSource(ints = {-180, 180})
    @DisplayName("Should keep the signed rotation when it is at either supported boundary")
    void shouldKeepTheSignedRotationWhenItIsAtEitherSupportedBoundary(int rotation) {
      var video = ProbeStreamInfo.newBuilder().setCodecType("video").setRotationDegrees(rotation);

      var stream = streamsOf(result(media -> media.setStreams(0, video))).getFirst();

      assertThat(stream.rotationDegrees()).hasValue(rotation);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @MethodSource("dolbyVisionStates")
    @DisplayName("Should keep each Dolby Vision state apart when a stream reports it")
    void shouldKeepEachDolbyVisionStateApartWhenAStreamReportsIt(
        com.streamarr.transcode.v1.VideoDynamicRange reported, DolbyVision expected) {
      var stream =
          streamsOf(
                  result(
                      media ->
                          media.setStreams(
                              0,
                              ProbeStreamInfo.newBuilder()
                                  .setCodecType("video")
                                  .setDynamicRange(reported))))
              .getFirst();

      assertThat(stream.dynamicRange()).map(VideoDynamicRange::dolbyVision).contains(expected);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    static Stream<Arguments> dolbyVisionStates() {
      var profile5Record =
          com.streamarr.transcode.v1.DolbyVisionConfiguration.newBuilder()
              .setProfile(5)
              .setLevel(6)
              .setBaseLayerCompatibilityId(0)
              .setBaseLayer(true)
              .setRpu(true);
      return Stream.of(
          Arguments.of(
              com.streamarr.transcode.v1.VideoDynamicRange.getDefaultInstance(),
              new DolbyVision.Unspecified()),
          Arguments.of(
              com.streamarr.transcode.v1.VideoDynamicRange.newBuilder()
                  .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                  .build(),
              new DolbyVision.Absent()),
          Arguments.of(
              com.streamarr.transcode.v1.VideoDynamicRange.newBuilder()
                  .setDolbyVisionPresent(DolbyVisionPresent.getDefaultInstance())
                  .build(),
              new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED)),
          Arguments.of(
              com.streamarr.transcode.v1.VideoDynamicRange.newBuilder()
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder().setDeclared(profile5Record))
                  .build(),
              new DolbyVision.Present(
                  Optional.of(
                      DolbyVisionConfiguration.builder()
                          .profile(OptionalInt.of(5))
                          .level(OptionalInt.of(6))
                          .baseLayerCompatibilityId(OptionalInt.of(0))
                          .baseLayer(true)
                          .rpu(true)
                          .build()),
                  Presence.UNSPECIFIED)),
          Arguments.of(
              com.streamarr.transcode.v1.VideoDynamicRange.newBuilder()
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(
                              com.streamarr.transcode.v1.DolbyVisionConfiguration.newBuilder()
                                  .setBaseLayer(true)))
                  .build(),
              new DolbyVision.Present(
                  Optional.of(DolbyVisionConfiguration.builder().baseLayer(true).build()),
                  Presence.UNSPECIFIED)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    @DisplayName("Should read no playback facts when the result's probe version reports none")
    void shouldReadNoPlaybackFactsWhenTheResultsProbeVersionReportsNone(int probeVersion) {
      var outcome =
          (ProbeOutcome.Success)
              mapper.map(
                  report(
                      ProbeAttemptResult.newBuilder()
                          .setProbeVersion(probeVersion)
                          .setMedia(
                              ProbeMediaInfo.newBuilder()
                                  .addStreams(
                                      ProbeStreamInfo.newBuilder()
                                          .setCodecType("video")
                                          .setBitDepth(10)
                                          .setCodecString("hvc1.2.4.L123.B0"))
                                  .setChosenVideoStreamIndex(0))
                          .build()));

      assertThat(outcome.chosenVideoStream()).isEmpty();
      assertThat(outcome.streams())
          .containsExactly(StreamInfo.builder().index(0).codecType("video").build());
    }
  }

  @Nested
  @DisplayName("Chosen Video Stream")
  class ChosenVideo {

    @Test
    @DisplayName("Should read the chosen video stream when cover art comes before it")
    void shouldReadTheChosenVideoStreamWhenCoverArtComesBeforeIt() {
      var outcome =
          (ProbeOutcome.Success)
              mapper.map(
                  report(
                      result(
                          media ->
                              media
                                  .setStreams(
                                      0,
                                      ProbeStreamInfo.newBuilder()
                                          .setIndex(0)
                                          .setCodecType("video")
                                          .setCodec("mjpeg")
                                          .setWidth(600)
                                          .setHeight(600))
                                  .addStreams(
                                      ProbeStreamInfo.newBuilder()
                                          .setIndex(1)
                                          .setCodecType("video")
                                          .setCodec("h264")
                                          .setWidth(1920)
                                          .setHeight(1080))
                                  .setChosenVideoStreamIndex(1))));

      var probe = outcome.mediaProbe();

      assertThat(outcome.chosenVideoStream()).map(ChosenVideoStream::streamIndex).contains(1);
      assertThat(probe.videoCodec()).isEqualTo("h264");
      assertThat(probe.width()).isEqualTo(1920);
      assertThat(probe.height()).isEqualTo(1080);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @Test
    @DisplayName("Should keep the output frame rate as an exact fraction with its basis and inputs")
    void shouldKeepTheOutputFrameRateAsAnExactFractionWithItsBasisAndInputs() {
      var rule =
          FrameRateRuleResult.newBuilder()
              .setOutputFrameRate(fraction(60, 1))
              .setBasis(
                  com.streamarr.transcode.v1.OutputFrameRateBasis
                      .OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN);
      rule.getInputsBuilder()
          .setBaseRate(fraction(120, 1))
          .setAverageRate(fraction(59_956, 1_000))
          .setFieldOrder(com.streamarr.transcode.v1.FieldOrder.FIELD_ORDER_PROGRESSIVE)
          .setTimeBase(fraction(1, 600))
          .getMatroskaStatisticsBuilder()
          .setNumberOfFrames(7_195)
          .getDurationBuilder()
          .setSeconds(120)
          .setNanos(5_000_001);
      rule.getInputsBuilder()
          .getPacketScanBuilder()
          .setMotionRate(59.97)
          .setScannedFrameCount(1_799)
          .getScannedDurationBuilder()
          .setSeconds(30)
          .setNanos(16_667);

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(rule)));

      assertThat(chosen.frameRateRule())
          .isEqualTo(
              FrameRateRule.builder()
                  .outputFrameRate(
                      Optional.of(new com.streamarr.server.domain.streaming.Fraction(60, 1)))
                  .basis(OutputFrameRateBasis.PACKET_SCAN)
                  .inputs(
                      FrameRateRuleInputs.builder()
                          .baseRate(
                              Optional.of(
                                  new com.streamarr.server.domain.streaming.Fraction(120, 1)))
                          .averageRate(
                              Optional.of(
                                  new com.streamarr.server.domain.streaming.Fraction(
                                      59_956, 1_000)))
                          .fieldOrder(Optional.of(FieldOrder.PROGRESSIVE))
                          .timeBase(
                              Optional.of(
                                  new com.streamarr.server.domain.streaming.Fraction(1, 600)))
                          .matroskaStatistics(
                              Optional.of(
                                  new MatroskaStatistics(
                                      OptionalLong.of(7_195),
                                      Optional.of(Duration.ofSeconds(120, 5_000_001)))))
                          .packetScan(
                              Optional.of(
                                  FrameRatePacketScan.builder()
                                      .motionRate(OptionalDouble.of(59.97))
                                      .scannedFrameCount(1_799)
                                      .scannedDuration(Optional.of(Duration.ofSeconds(30, 16_667)))
                                      .build()))
                          .build())
                  .build());
    }

    @Test
    @DisplayName("Should read no Matroska statistics when the worker sends neither tag")
    void shouldReadNoMatroskaStatisticsWhenTheWorkerSendsNeitherTag() {
      var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
      rule.getInputsBuilder().getMatroskaStatisticsBuilder();

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(rule)));

      assertThat(chosen.frameRateRule().inputs().matroskaStatistics()).isEmpty();
    }

    @Test
    @DisplayName("Should reject the result when its keyframes belong to another video stream")
    void shouldRejectTheResultWhenItsKeyframesBelongToAnotherVideoStream() {
      var report = keyframesReport(0);

      assertThatThrownBy(() -> mapper.map(report)).isInstanceOf(ProbeExecutionException.class);
    }

    @Test
    @DisplayName("Should keep the keyframes when they belong to the chosen video stream")
    void shouldKeepTheKeyframesWhenTheyBelongToTheChosenVideoStream() {
      var outcome = (ProbeOutcome.Success) mapper.map(keyframesReport(1));

      assertThat(outcome.keyframes())
          .isInstanceOfSatisfying(
              KeyframeKnowledge.Known.class,
              known -> assertThat(known.positions().streamIndex()).isEqualTo(1));
    }

    // Cover art at stream 0 and the chosen video stream at stream 1.
    private static ProbeAttemptReport keyframesReport(int keyframeStreamIndex) {
      var result =
          result(
              media ->
                  media
                      .addStreams(ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video"))
                      .setChosenVideoStreamIndex(1)
                      .setKeyframes(
                          ProbeKeyframes.newBuilder()
                              .setPositions(
                                  KeyframePositions.newBuilder()
                                      .setStreamIndex(keyframeStreamIndex)
                                      .setTimeBaseNumerator(1)
                                      .setTimeBaseDenominator(90_000)
                                      .setSource(
                                          KeyframePositionSource
                                              .KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
                                      .setKeyframeCount(1))));
      return new ProbeAttemptReport(
          result,
          List.of(
              ProbeKeyframePositionsPart.newBuilder()
                  .addPresentationTimeDeltas(0)
                  .addDecodeTimeDeltas(0)
                  .build()));
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @Test
    @DisplayName("Should read no output frame rate when the frame-rate rule refused one")
    void shouldReadNoOutputFrameRateWhenTheFrameRateRuleRefusedOne() {
      var refused =
          FrameRateRuleResult.newBuilder()
              .setBasis(
                  com.streamarr.transcode.v1.OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED);
      refused.getInputsBuilder().getPacketScanBuilder().setScannedFrameCount(1);

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(refused)));

      assertThat(chosen.frameRateRule().outputFrameRate()).isEmpty();
      assertThat(chosen.frameRateRule().basis()).isEqualTo(OutputFrameRateBasis.REFUSED);
    }
  }

  @Nested
  @DisplayName("Contract Violations")
  class ContractViolations {

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @DisplayName("Should reject the result when it breaks the probe version 4 contract")
    void shouldRejectTheResultWhenItBreaksTheProbeVersion4Contract(
        String violation, UnaryOperator<ProbeMediaInfo.Builder> breakContract) {
      var report = report(result(breakContract));

      assertThatThrownBy(() -> mapper.map(report)).isInstanceOf(ProbeExecutionException.class);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    static Stream<Arguments> violations() {
      return Stream.of(
          violation("no chosen video stream", ProbeMediaInfo.Builder::clearChosenVideoStreamIndex),
          violation(
              "a chosen stream that is not a video stream",
              media ->
                  media
                      .addStreams(ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio"))
                      .setChosenVideoStreamIndex(1)),
          violation("a chosen stream that is missing", media -> media.setChosenVideoStreamIndex(7)),
          violation("no frame-rate rule", ProbeMediaInfo.Builder::clearFrameRateRule),
          violation(
              "a frame-rate rule without a basis",
              media ->
                  media.setFrameRateRule(ruleWithBasis(null).setOutputFrameRate(fraction(25, 1)))),
          violation(
              "a refused frame-rate rule with an output frame rate",
              media ->
                  media.setFrameRateRule(
                      ruleWithBasis(
                              com.streamarr.transcode.v1.OutputFrameRateBasis
                                  .OUTPUT_FRAME_RATE_BASIS_REFUSED)
                          .setOutputFrameRate(fraction(25, 1)))),
          violation(
              "a chosen frame-rate rule without an output frame rate",
              media ->
                  media.setFrameRateRule(
                      ruleWithBasis(
                          com.streamarr.transcode.v1.OutputFrameRateBasis
                              .OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES))),
          violation(
              "an output frame rate with a zero denominator",
              media ->
                  media.setFrameRateRule(
                      WorkerProbeResultFixture.declaredRateRuleBuilder()
                          .setOutputFrameRate(fraction(25, 0)))),
          violation(
              "a rotation beyond 180 degrees",
              media -> media.setStreams(0, video().setRotationDegrees(270))),
          violation(
              "a rotation below -180 degrees",
              media -> media.setStreams(0, video().setRotationDegrees(-181))),
          violation(
              "the minimum signed integer as a rotation",
              media -> media.setStreams(0, video().setRotationDegrees(Integer.MIN_VALUE))),
          violation(
              "a blank codec string", media -> media.setStreams(0, video().setCodecString(" "))),
          violation("a bit depth of 0", media -> media.setStreams(0, video().setBitDepth(0))),
          violation(
              "a crop beyond the integer range",
              media ->
                  media.setStreams(
                      0,
                      video()
                          .setContainerCropping(
                              com.streamarr.transcode.v1.ContainerCropping.newBuilder()
                                  .setTop(-1)))),
          violation(
              "a motion rate of 0",
              media -> {
                var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
                rule.getInputsBuilder().getPacketScanBuilder().setMotionRate(0);
                return media.setFrameRateRule(rule);
              }),
          violation(
              "a Matroska frame count past the supported range",
              media -> {
                var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
                rule.getInputsBuilder().getMatroskaStatisticsBuilder().setNumberOfFrames(-1);
                return media.setFrameRateRule(rule);
              }),
          violation(
              "a motion rate that is not a number",
              media -> {
                var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
                rule.getInputsBuilder().getPacketScanBuilder().setMotionRate(Double.NaN);
                return media.setFrameRateRule(rule);
              }),
          violation(
              "a zero fraction denominator",
              media ->
                  media.setFrameRateRule(
                      WorkerProbeResultFixture.declaredRateRuleBuilder()
                          .setOutputFrameRate(fraction(24, 0)))),
          violation(
              "a negative Matroska duration",
              media -> {
                var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
                rule.getInputsBuilder()
                    .getMatroskaStatisticsBuilder()
                    .getDurationBuilder()
                    .setSeconds(-1);
                return media.setFrameRateRule(rule);
              }));
    }

    private static Arguments violation(
        String violation, UnaryOperator<ProbeMediaInfo.Builder> breakContract) {
      return Arguments.of(violation, breakContract);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    private static FrameRateRuleResult.Builder ruleWithBasis(
        com.streamarr.transcode.v1.OutputFrameRateBasis basis) {
      var rule = WorkerProbeResultFixture.declaredRateRuleBuilder().clearOutputFrameRate();
      return basis == null ? rule.clearBasis() : rule.setBasis(basis);
    }

    private static ProbeStreamInfo.Builder video() {
      return ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video");
    }
  }

  // ADR 0036 and ADR 0037 name each value the same way in the worker contract and the domain.
  @Nested
  @DisplayName("Shared Vocabulary")
  class SharedVocabulary {

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @EnumSource(
        value = com.streamarr.transcode.v1.CompatibleBase.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = "UNRECOGNIZED")
    @DisplayName("Should read each compatible base when a stream reports it")
    void shouldReadEachCompatibleBaseWhenAStreamReportsIt(
        com.streamarr.transcode.v1.CompatibleBase base) {
      var video = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video");
      video.getDynamicRangeBuilder().setCompatibleBase(base);

      var range = streamsOf(result(media -> media.setStreams(0, video))).getFirst().dynamicRange();

      assertThat(range.orElseThrow().compatibleBase().name())
          .isEqualTo(base.name().replace("COMPATIBLE_BASE_", ""));
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @EnumSource(
        value = com.streamarr.transcode.v1.Presence.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = "UNRECOGNIZED")
    @DisplayName("Should read each HDR10+ presence when a stream reports it")
    void shouldReadEachHdr10PlusPresenceWhenAStreamReportsIt(
        com.streamarr.transcode.v1.Presence presence) {
      var video = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video");
      video.getDynamicRangeBuilder().setHdr10Plus(presence);

      var range = streamsOf(result(media -> media.setStreams(0, video))).getFirst().dynamicRange();

      assertThat(range.orElseThrow().hdr10Plus().name())
          .isEqualTo(presence.name().replace("PRESENCE_", ""));
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @CsvSource({
      "CHROMA_SUBSAMPLING_420, CHROMA_420",
      "CHROMA_SUBSAMPLING_422, CHROMA_422",
      "CHROMA_SUBSAMPLING_444, CHROMA_444",
      "CHROMA_SUBSAMPLING_MONOCHROME, MONOCHROME"
    })
    @DisplayName("Should read each chroma subsampling when a stream reports it")
    void shouldReadEachChromaSubsamplingWhenAStreamReportsIt(
        com.streamarr.transcode.v1.ChromaSubsampling reported, ChromaSubsampling read) {
      var video =
          ProbeStreamInfo.newBuilder()
              .setIndex(0)
              .setCodecType("video")
              .setChromaSubsampling(reported);

      var stream = streamsOf(result(media -> media.setStreams(0, video))).getFirst();

      assertThat(stream.chromaSubsampling()).contains(read);
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @EnumSource(
        value = com.streamarr.transcode.v1.FieldOrder.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = {"FIELD_ORDER_UNSPECIFIED", "UNRECOGNIZED"})
    @DisplayName("Should read each field order when the frame-rate rule reports it")
    void shouldReadEachFieldOrderWhenTheFrameRateRuleReportsIt(
        com.streamarr.transcode.v1.FieldOrder order) {
      var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
      rule.getInputsBuilder().setFieldOrder(order);

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(rule)));

      assertThat(chosen.frameRateRule().inputs().fieldOrder().map(Enum::name))
          .contains(order.name().replace("FIELD_ORDER_", ""));
    }

    @SuppressWarnings("checkstyle:fullyQualifiedName")
    @ParameterizedTest
    @EnumSource(
        value = com.streamarr.transcode.v1.OutputFrameRateBasis.class,
        names = {
          "OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES",
          "OUTPUT_FRAME_RATE_BASIS_MATROSKA_STATISTICS",
          "OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN",
          "OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK"
        })
    @DisplayName("Should read each basis that chooses a rate when the frame-rate rule reports it")
    void shouldReadEachBasisThatChoosesARateWhenTheFrameRateRuleReportsIt(
        com.streamarr.transcode.v1.OutputFrameRateBasis basis) {
      var rule = WorkerProbeResultFixture.declaredRateRuleBuilder().setBasis(basis);

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(rule)));

      assertThat(chosen.frameRateRule().basis().name())
          .isEqualTo(basis.name().replace("OUTPUT_FRAME_RATE_BASIS_", ""));
    }

    @Test
    @DisplayName("Should keep Matroska statistics that carry only a frame count")
    void shouldKeepMatroskaStatisticsThatCarryOnlyAFrameCount() {
      var rule = WorkerProbeResultFixture.declaredRateRuleBuilder();
      rule.getInputsBuilder().getMatroskaStatisticsBuilder().setNumberOfFrames(2_880);

      var chosen = chosenVideoStreamOf(result(media -> media.setFrameRateRule(rule)));

      assertThat(chosen.frameRateRule().inputs().matroskaStatistics())
          .contains(new MatroskaStatistics(OptionalLong.of(2_880), Optional.empty()));
    }
  }

  private List<StreamInfo> streamsOf(ProbeAttemptResult result) {
    return ((ProbeOutcome.Success) mapper.map(report(result))).streams();
  }

  private ChosenVideoStream chosenVideoStreamOf(ProbeAttemptResult result) {
    return ((ProbeOutcome.Success) mapper.map(report(result))).chosenVideoStream().orElseThrow();
  }

  private static Fraction.Builder fraction(int numerator, int denominator) {
    return Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator);
  }

  private static ProbeAttemptResult result(UnaryOperator<ProbeMediaInfo.Builder> media) {
    return ProbeAttemptResult.newBuilder()
        .setProbeVersion(PROBE_VERSION_4)
        .setMedia(media.apply(WorkerProbeResultFixture.version4MediaBuilder()))
        .build();
  }

  private static ProbeAttemptReport report(ProbeAttemptResult result) {
    return new ProbeAttemptReport(result, List.of());
  }
}
