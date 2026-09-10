package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Target Tests")
class TargetTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("rangeCases")
  @DisplayName(
      "Should respect inclusive profile bounds when a swatch touches or crosses a boundary")
  void shouldRespectInclusiveProfileBoundsWhenSwatchTouchesOrCrossesBoundary(RangeCase sample) {
    var hsl = new float[] {0f, sample.saturation(), sample.lightness()};

    assertThat(sample.target().accepts(hsl)).as(sample.toString()).isEqualTo(sample.accepted());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scoreCases")
  @DisplayName("Should apply the AndroidX score weights when one input departs from its target")
  void shouldApplyAndroidxScoreWeightsWhenOneInputDepartsFromItsTarget(ScoreCase sample) {
    var hsl = new float[] {0f, sample.saturation(), sample.lightness()};

    assertThat(sample.target().score(hsl, sample.populationShare()))
        .isCloseTo(sample.expected(), within(1e-6f));
  }

  private static Stream<RangeCase> rangeCases() {
    return Stream.of(
            Profile.builder()
                .target(Target.VIBRANT)
                .minSaturation(0.35f)
                .maxSaturation(1f)
                .minLightness(0.3f)
                .maxLightness(0.7f)
                .build(),
            Profile.builder()
                .target(Target.LIGHT_VIBRANT)
                .minSaturation(0.35f)
                .maxSaturation(1f)
                .minLightness(0.55f)
                .maxLightness(1f)
                .build(),
            Profile.builder()
                .target(Target.DARK_VIBRANT)
                .minSaturation(0.35f)
                .maxSaturation(1f)
                .minLightness(0f)
                .maxLightness(0.45f)
                .build(),
            Profile.builder()
                .target(Target.LIGHT_MUTED)
                .minSaturation(0f)
                .maxSaturation(0.4f)
                .minLightness(0.55f)
                .maxLightness(1f)
                .build(),
            Profile.builder()
                .target(Target.MUTED)
                .minSaturation(0f)
                .maxSaturation(0.4f)
                .minLightness(0.3f)
                .maxLightness(0.7f)
                .build(),
            Profile.builder()
                .target(Target.DARK_MUTED)
                .minSaturation(0f)
                .maxSaturation(0.4f)
                .minLightness(0f)
                .maxLightness(0.45f)
                .build())
        .flatMap(Profile::cases);
  }

  private static Stream<ScoreCase> scoreCases() {
    return Stream.of(
        vibrantScore().expected(1f).build(),
        vibrantScore().lightness(0.3f).expected(0.896f).build(),
        vibrantScore().saturation(0.8f).expected(0.952f).build(),
        vibrantScore().populationShare(0.8f).expected(0.952f).build(),
        vibrantScore().target(Target.DARK_VIBRANT).lightness(0.26f).expected(1f).build(),
        vibrantScore().target(Target.LIGHT_VIBRANT).lightness(0.74f).expected(1f).build(),
        vibrantScore()
            .target(Target.DARK_MUTED)
            .saturation(0.3f)
            .lightness(0.26f)
            .expected(1f)
            .build(),
        vibrantScore().target(Target.MUTED).saturation(0.3f).expected(1f).build(),
        vibrantScore()
            .target(Target.LIGHT_MUTED)
            .saturation(0.3f)
            .lightness(0.74f)
            .expected(1f)
            .build());
  }

  private static ScoreCase.ScoreCaseBuilder vibrantScore() {
    return ScoreCase.builder()
        .target(Target.VIBRANT)
        .saturation(1f)
        .lightness(0.5f)
        .populationShare(1f);
  }

  @Builder
  private record ScoreCase(
      Target target, float saturation, float lightness, float populationShare, float expected) {}

  @Builder
  private record RangeCase(Target target, float saturation, float lightness, boolean accepted) {}

  @Builder
  private record Profile(
      Target target,
      float minSaturation,
      float maxSaturation,
      float minLightness,
      float maxLightness) {
    Stream<RangeCase> cases() {
      return Stream.of(
          sample().saturation(minSaturation).lightness(minLightness).accepted(true).build(),
          sample().saturation(maxSaturation).lightness(maxLightness).accepted(true).build(),
          sample().saturation(minSaturation).lightness(maxLightness).accepted(true).build(),
          sample().saturation(maxSaturation).lightness(minLightness).accepted(true).build(),
          sample().saturation(minSaturation - 0.01f).accepted(false).build(),
          sample().saturation(maxSaturation + 0.01f).accepted(false).build(),
          sample().lightness(minLightness - 0.01f).accepted(false).build(),
          sample().lightness(maxLightness + 0.01f).accepted(false).build());
    }

    private RangeCase.RangeCaseBuilder sample() {
      return RangeCase.builder()
          .target(target)
          .saturation((minSaturation + maxSaturation) / 2f)
          .lightness((minLightness + maxLightness) / 2f);
    }
  }
}
