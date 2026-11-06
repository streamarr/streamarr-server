package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.WorkerProbeResultFixture.pqDolbyVisionOf;
import static com.streamarr.server.fixtures.WorkerProbeResultFixture.reportedEnhancementLayer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionEnhancementLayer;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Version 8 Facts Mapping Tests")
class RemoteProbeResultVersion8FactsMapperTest {

  private static final int VERSION_8 =
      ProbeVersion.FIRST_TO_REPORT_MAXIMUM_FRAME_RATE_AND_RPU_FACTS;
  private static final int VERSION_7 = VERSION_8 - 1;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Test
  @DisplayName(
      "Should keep the maximum frame rate, the enhancement layer and the level 6 light levels when"
          + " probe version 8 reports them")
  void shouldKeepTheVersion8FactsWhenProbeVersion8ReportsThem() {
    var outcome = success(VERSION_8, profile7Media());

    assertThat(outcome.streams().getFirst().maximumFrameRate())
        .contains(new Fraction(60_000, 1_001));
    assertThat(dolbyVisionOf(outcome).enhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.FEL);
    assertThat(dolbyVisionOf(outcome).rpuContentLightLevel())
        .contains(new ContentLightLevel(1_000, 400));
  }

  @Test
  @DisplayName(
      "Should report an unknown maximum, an unspecified layer and no level 6 values when a probe"
          + " version 8 result leaves them out")
  void shouldReportUnknownVersion8FactsWhenAProbeVersion8ResultLeavesThemOut() {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    pqDolbyVisionOf(media.getStreamsBuilder(0));

    var outcome = success(VERSION_8, media);

    assertThat(outcome.streams().getFirst().maximumFrameRate()).isEmpty();
    assertThat(dolbyVisionOf(outcome).enhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.UNSPECIFIED);
    assertThat(dolbyVisionOf(outcome).rpuContentLightLevel()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore every probe version 8 fact when a probe version 7 result carries it")
  void shouldIgnoreEveryVersion8FactWhenAProbeVersion7ResultCarriesIt() {
    var outcome = success(VERSION_7, profile7Media());

    assertThat(outcome.streams().getFirst().maximumFrameRate()).isEmpty();
    assertThat(dolbyVisionOf(outcome).enhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.UNSPECIFIED);
    assertThat(dolbyVisionOf(outcome).rpuContentLightLevel()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(DolbyVisionEnhancementLayer.class)
  @DisplayName("Should map each enhancement layer when probe version 8 reports it")
  void shouldMapEachEnhancementLayerWhenProbeVersion8ReportsIt(DolbyVisionEnhancementLayer layer) {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    pqDolbyVisionOf(media.getStreamsBuilder(0))
        .setEnhancementLayer(reportedEnhancementLayer(layer.name()));

    var outcome = success(VERSION_8, media);

    assertThat(dolbyVisionOf(outcome).enhancementLayer()).isEqualTo(layer);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factsOutOfContract")
  @DisplayName("Should reject the result when probe version 8 reports a fact out of its contract")
  void shouldRejectTheResultWhenProbeVersion8ReportsAFactOutOfItsContract(
      String description, Consumer<ProbeMediaInfo.Builder> fact) {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    fact.accept(media);
    var report = report(VERSION_8, media);

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  static Stream<Arguments> factsOutOfContract() {
    return Stream.of(
        Arguments.of("a maximum frame rate with a zero numerator", maximumFrameRate(0, 1)),
        Arguments.of("a maximum frame rate with a zero denominator", maximumFrameRate(24, 0)),
        Arguments.of("a maximum frame rate above the integer range", maximumFrameRate(-1, 1)),
        Arguments.of("a maximum frame rate on an audio stream", maximumFrameRateOnAudio()),
        Arguments.of("a level 6 MaxCLL above 65535", rpuContentLightLevel(65_536, 400)),
        Arguments.of("a level 6 MaxFALL above 65535", rpuContentLightLevel(1_000, 65_536)));
  }

  private static Consumer<ProbeMediaInfo.Builder> maximumFrameRate(int numerator, int denominator) {
    return media ->
        media
            .getStreamsBuilder(0)
            .getMaximumFrameRateBuilder()
            .setNumerator(numerator)
            .setDenominator(denominator);
  }

  private static Consumer<ProbeMediaInfo.Builder> maximumFrameRateOnAudio() {
    return media -> {
      var audio = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio");
      audio.getMaximumFrameRateBuilder().setNumerator(24).setDenominator(1);
      media.addStreams(audio);
    };
  }

  private static Consumer<ProbeMediaInfo.Builder> rpuContentLightLevel(
      int maxContentLightLevel, int maxFrameAverageLightLevel) {
    return media ->
        pqDolbyVisionOf(media.getStreamsBuilder(0))
            .getRpuContentLightLevelBuilder()
            .setMaxContentLightLevel(maxContentLightLevel)
            .setMaxFrameAverageLightLevel(maxFrameAverageLightLevel);
  }

  // A single-track profile 7 stream with a full enhancement layer, whose faster sections run at
  // 59.94 fps.
  private static ProbeMediaInfo.Builder profile7Media() {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    var video = media.getStreamsBuilder(0).setCodec("hevc");
    video.getMaximumFrameRateBuilder().setNumerator(60_000).setDenominator(1_001);
    var dolbyVision = pqDolbyVisionOf(video).setEnhancementLayer(reportedEnhancementLayer("FEL"));
    dolbyVision
        .getRpuContentLightLevelBuilder()
        .setMaxContentLightLevel(1_000)
        .setMaxFrameAverageLightLevel(400);
    return media;
  }

  private static DolbyVision.Present dolbyVisionOf(ProbeOutcome.Success outcome) {
    return (DolbyVision.Present)
        outcome.streams().getFirst().dynamicRange().orElseThrow().dolbyVision();
  }

  private ProbeOutcome.Success success(int version, ProbeMediaInfo.Builder media) {
    return (ProbeOutcome.Success) mapper.map(report(version, media));
  }

  private static ProbeAttemptReport report(int version, ProbeMediaInfo.Builder media) {
    return new ProbeAttemptReport(
        ProbeAttemptResult.newBuilder().setProbeVersion(version).setMedia(media).build(),
        List.of());
  }
}
