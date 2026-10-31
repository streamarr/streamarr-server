package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Stream Copy Tests")
class VideoStreamCopyTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("frameRateRules")
  @DisplayName(
      "Should run a copy at the rate the source's frames run at when the frame-rate rule snapped"
          + " that rate")
  void shouldRunACopyAtTheRateTheSourcesFramesRunAtWhenTheFrameRateRuleSnappedThatRate(
      String scenario, FrameRateRule rule, OptionalDouble expected) {
    var source =
        MediaProbe.builder()
            .framerate(OptionalDouble.empty())
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .keyframes(new KeyframeKnowledge.NotReported())
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder().frameRateRule(rule).build()))
            .build();

    var copy = VideoStreamCopy.of(source).orElseThrow();

    assertThat(copy.frameRate()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}×{1}")
  @CsvSource({"0, 1080", "1920, 0"})
  @DisplayName("Should leave a copy's decoded size empty when the probe could not read a dimension")
  void shouldLeaveACopysDecodedSizeEmptyWhenTheProbeCouldNotReadADimension(int width, int height) {
    var source =
        MediaProbe.builder()
            .framerate(OptionalDouble.empty())
            .width(width)
            .height(height)
            .videoCodec("h264")
            .keyframes(new KeyframeKnowledge.NotReported())
            .videoPlaybackFacts(
                Optional.of(VideoPlaybackFactsFixture.sdrH264FactsBuilder().build()))
            .build();

    var copy = VideoStreamCopy.of(source).orElseThrow();

    assertThat(copy.decodedSize()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse to describe a copy when the caller passes no source")
  void shouldRefuseToDescribeACopyWhenTheCallerPassesNoSource() {
    assertThatThrownBy(() -> VideoStreamCopy.of(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("source");
  }

  // ADR 0037's rule snaps a measured or declared rate to a listed rate: it raises a rate below 10
  // fps to 10, counts a rate from 60 to 60.3 fps as 60, divides a faster rate down to a listed
  // rate and otherwise takes the nearest listed rate. A copy keeps the unsnapped rate in every
  // case.
  static Stream<Arguments> frameRateRules() {
    return Stream.of(
        Arguments.of(
            "declared 23.976 fps",
            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24_000, 1_001)),
            OptionalDouble.of(24_000.0 / 1_001)),
        Arguments.of(
            "declared rate kept against Matroska's statistics",
            FrameRateRule.builder()
                .outputFrameRate(Optional.of(new Fraction(24, 1)))
                .basis(OutputFrameRateBasis.MATROSKA_STATISTICS)
                .inputs(FrameRateRuleInputs.builder().build())
                .build(),
            OptionalDouble.of(24)),
        Arguments.of(
            "120 fps motion divided to 60",
            VideoPlaybackFactsFixture.packetScanRule(120, new Fraction(60, 1)),
            OptionalDouble.of(120)),
        Arguments.of(
            "119.88 fps motion divided to 59.94",
            VideoPlaybackFactsFixture.packetScanRule(119.88, new Fraction(60_000, 1_001)),
            OptionalDouble.of(119.88)),
        Arguments.of(
            "65 fps motion that no listed rate divides",
            VideoPlaybackFactsFixture.packetScanRule(65, new Fraction(60, 1)),
            OptionalDouble.of(65)),
        Arguments.of(
            "60.3 fps motion that the rule counts as 60",
            VideoPlaybackFactsFixture.packetScanRule(60.3, new Fraction(60, 1)),
            OptionalDouble.of(60.3)),
        Arguments.of(
            "59.95 fps motion rounded to 59.94",
            VideoPlaybackFactsFixture.packetScanRule(59.95, new Fraction(60_000, 1_001)),
            OptionalDouble.of(59.95)),
        Arguments.of(
            "37.5 fps motion rounded down to 30",
            VideoPlaybackFactsFixture.packetScanRule(37.5, new Fraction(30, 1)),
            OptionalDouble.of(37.5)),
        Arguments.of(
            "8 fps motion raised to 10",
            VideoPlaybackFactsFixture.packetScanRule(8, new Fraction(10, 1)),
            OptionalDouble.of(8)),
        Arguments.of(
            "packet scan without a motion rate",
            FrameRateRule.builder()
                .outputFrameRate(Optional.of(new Fraction(30, 1)))
                .basis(OutputFrameRateBasis.PACKET_SCAN)
                .inputs(
                    FrameRateRuleInputs.builder()
                        .packetScan(
                            Optional.of(FrameRatePacketScan.builder().scannedFrameCount(1).build()))
                        .build())
                .build(),
            OptionalDouble.of(30)),
        Arguments.of(
            "120 fps average rate divided to 60 after a scan found fewer than 2 frames",
            VideoPlaybackFactsFixture.declaredRateFallbackRule(
                FrameRateRuleInputs.builder()
                    .averageRate(Optional.of(new Fraction(120, 1)))
                    .baseRate(Optional.of(new Fraction(24, 1)))
                    .build(),
                new Fraction(60, 1)),
            OptionalDouble.of(120)),
        Arguments.of(
            "60.3 fps average rate that the rule counts as 60 after a scan found fewer than 2 frames",
            VideoPlaybackFactsFixture.declaredRateFallbackRule(
                FrameRateRuleInputs.builder()
                    .averageRate(Optional.of(new Fraction(603, 10)))
                    .build(),
                new Fraction(60, 1)),
            OptionalDouble.of(60.3)),
        Arguments.of(
            "base rate after a scan found fewer than 2 frames and the average rate is not usable",
            VideoPlaybackFactsFixture.declaredRateFallbackRule(
                FrameRateRuleInputs.builder()
                    .averageRate(Optional.of(new Fraction(1_000, 1)))
                    .baseRate(Optional.of(new Fraction(75, 2)))
                    .build(),
                new Fraction(30, 1)),
            OptionalDouble.of(37.5)),
        Arguments.of(
            "declared-rate fallback without a declared rate",
            VideoPlaybackFactsFixture.declaredRateFallbackRule(
                FrameRateRuleInputs.builder().build(), new Fraction(30, 1)),
            OptionalDouble.of(30)),
        Arguments.of(
            "refused rule",
            FrameRateRule.builder()
                .outputFrameRate(Optional.empty())
                .basis(OutputFrameRateBasis.REFUSED)
                .inputs(FrameRateRuleInputs.builder().build())
                .build(),
            OptionalDouble.empty()));
  }
}
