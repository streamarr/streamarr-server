package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Target Tests")
class TargetTest {

  @Test
  @DisplayName("Should accept swatch when saturation and lightness are inside the target ranges")
  void shouldAcceptSwatchWhenSaturationAndLightnessAreInsideTheTargetRanges() {
    assertThat(Target.DARK_VIBRANT.accepts(hsl(0.35f, 0.45f))).isTrue();
    assertThat(Target.LIGHT_MUTED.accepts(hsl(0.4f, 0.55f))).isTrue();
    assertThat(Target.VIBRANT.accepts(hsl(1f, 0.3f))).isTrue();
    assertThat(Target.MUTED.accepts(hsl(0f, 0.7f))).isTrue();
  }

  @Test
  @DisplayName("Should reject swatch when lightness leaves the target range")
  void shouldRejectSwatchWhenLightnessLeavesTheTargetRange() {
    assertThat(Target.DARK_VIBRANT.accepts(hsl(0.5f, 0.46f))).isFalse();
    assertThat(Target.LIGHT_VIBRANT.accepts(hsl(0.5f, 0.54f))).isFalse();
    assertThat(Target.MUTED.accepts(hsl(0.2f, 0.71f))).isFalse();
    assertThat(Target.VIBRANT.accepts(hsl(0.5f, 0.29f))).isFalse();
  }

  @Test
  @DisplayName("Should reject swatch when saturation leaves the target range")
  void shouldRejectSwatchWhenSaturationLeavesTheTargetRange() {
    assertThat(Target.VIBRANT.accepts(hsl(0.34f, 0.5f))).isFalse();
    assertThat(Target.DARK_MUTED.accepts(hsl(0.41f, 0.2f))).isFalse();
  }

  @Test
  @DisplayName("Should score one when swatch sits on target and holds the largest population")
  void shouldScoreOneWhenSwatchSitsOnTargetAndHoldsTheLargestPopulation() {
    assertThat(Target.VIBRANT.score(hsl(1f, 0.5f), 1f)).isCloseTo(1f, within(1e-6f));
    assertThat(Target.DARK_MUTED.score(hsl(0.3f, 0.26f), 1f)).isCloseTo(1f, within(1e-6f));
    assertThat(Target.LIGHT_VIBRANT.score(hsl(1f, 0.74f), 1f)).isCloseTo(1f, within(1e-6f));
  }

  @Test
  @DisplayName("Should penalize lightness more than saturation or population when scoring")
  void shouldPenalizeLightnessMoreThanSaturationOrPopulationWhenScoring() {
    var offLightness = Target.VIBRANT.score(hsl(1f, 0.3f), 1f);
    var offSaturation = Target.VIBRANT.score(hsl(0.8f, 0.5f), 1f);
    var offPopulation = Target.VIBRANT.score(hsl(1f, 0.5f), 0.8f);

    assertThat(offLightness).isLessThan(offSaturation);
    assertThat(offLightness).isLessThan(offPopulation);
  }

  @Test
  @DisplayName("Should score vibrant first when iterating targets in declaration order")
  void shouldScoreVibrantFirstWhenIteratingTargetsInDeclarationOrder() {
    assertThat(Target.values()[0])
        .as("VIBRANT claims its swatch before LIGHT_VIBRANT can, keeping primary unchanged")
        .isEqualTo(Target.VIBRANT);
  }

  private static float[] hsl(float saturation, float lightness) {
    return new float[] {0f, saturation, lightness};
  }
}
