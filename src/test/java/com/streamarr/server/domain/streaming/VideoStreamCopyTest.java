package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Stream Copy Tests")
class VideoStreamCopyTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("frameRateRules")
  @DisplayName("Should run a copy at the source's motion rate when the frame-rate rule divided it")
  void shouldRunACopyAtTheSourcesMotionRateWhenTheFrameRateRuleDividedIt(
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

  // ADR 0037's rule divides a motion rate above 60.3 fps down to a listed rate, and counts a
  // motion rate from 60 to 60.3 fps as 60.
  static Stream<Arguments> frameRateRules() {
    return Stream.of(
        Arguments.of(
            "declared 23.976 fps",
            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24_000, 1_001)),
            OptionalDouble.of(24_000.0 / 1_001)),
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
            "60.3 fps motion counted as 60",
            VideoPlaybackFactsFixture.packetScanRule(60.3, new Fraction(60, 1)),
            OptionalDouble.of(60)),
        Arguments.of(
            "59.95 fps motion rounded to 59.94",
            VideoPlaybackFactsFixture.packetScanRule(59.95, new Fraction(60_000, 1_001)),
            OptionalDouble.of(60_000.0 / 1_001)),
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
