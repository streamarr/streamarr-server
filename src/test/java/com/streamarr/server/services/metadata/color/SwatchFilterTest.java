package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Swatch Filter Tests")
class SwatchFilterTest {

  @Test
  @DisplayName("Should exclude color when lightness equals black maximum")
  void shouldExcludeColorWhenLightnessEqualsBlackMaximum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {0f, 0f, 0.05f})).isFalse();
  }

  @Test
  @DisplayName("Should allow color when lightness exceeds black maximum")
  void shouldAllowColorWhenLightnessExceedsBlackMaximum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {0f, 0f, Math.nextUp(0.05f)}))
        .isTrue();
  }

  @Test
  @DisplayName("Should exclude color when lightness equals white minimum")
  void shouldExcludeColorWhenLightnessEqualsWhiteMinimum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {0f, 0f, 0.95f})).isFalse();
  }

  @Test
  @DisplayName("Should allow color when lightness is below white minimum")
  void shouldAllowColorWhenLightnessIsBelowWhiteMinimum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {0f, 0f, Math.nextDown(0.95f)}))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(floats = {10f, 37f})
  @DisplayName("Should exclude skin tone when hue equals inclusive boundary")
  void shouldExcludeSkinToneWhenHueEqualsInclusiveBoundary(float hue) {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {hue, 0.5f, 0.5f})).isFalse();
  }

  @Test
  @DisplayName("Should allow skin tone when hue is outside inclusive range")
  void shouldAllowSkinToneWhenHueIsOutsideInclusiveRange() {
    assertThat(List.of(Math.nextDown(10f), Math.nextUp(37f)))
        .hasSize(2)
        .allSatisfy(
            hue ->
                assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {hue, 0.5f, 0.5f}))
                    .isTrue());
  }

  @Test
  @DisplayName("Should exclude skin tone when saturation equals maximum")
  void shouldExcludeSkinToneWhenSaturationEqualsMaximum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {20f, 0.82f, 0.5f})).isFalse();
  }

  @Test
  @DisplayName("Should allow skin tone when saturation exceeds maximum")
  void shouldAllowSkinToneWhenSaturationExceedsMaximum() {
    assertThat(SwatchFilter.DEFAULT.isAllowed(0, new float[] {20f, Math.nextUp(0.82f), 0.5f}))
        .isTrue();
  }
}
