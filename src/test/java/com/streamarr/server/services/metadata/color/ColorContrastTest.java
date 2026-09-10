package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.support.WcagContrast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Color Contrast Tests")
class ColorContrastTest {

  @ParameterizedTest(name = "RGB {0}: luminance {1}")
  @CsvSource({
    "0xFFFFFF, 1.0", "0x000000, 0.0", "0x808080, 0.21586050011389926",
    "0xFF0000, 0.2126", "0x00FF00, 0.7152", "0x0000FF, 0.0722",
    "0x0A0A0A, 0.003035269835488375", "0x0B0B0B, 0.003346535763899161"
  })
  @DisplayName("Should match WCAG luminance when channels have known reference values")
  void shouldMatchWcagLuminanceWhenChannelsHaveKnownReferenceValues(int rgb, double expected) {
    assertThat(WcagContrast.luminance(rgb))
        .as("independent oracle")
        .isCloseTo(expected, within(1e-12));
    assertThat(ColorContrast.relativeLuminance(rgb)).isCloseTo(expected, within(1e-12));
  }

  @ParameterizedTest(name = "foreground {0}, background {1}, ratio {2}")
  @CsvSource({
    "0xFFFFFF, 0x000000, 21.0", "0x000000, 0xFFFFFF, 21.0",
    "0x808080, 0x808080, 1.0", "0xFF0000, 0x00FF00, 2.913937547600913",
    "0x0000FF, 0xFFFFFF, 8.592471358428805", "0x808080, 0xFFFFFF, 3.9494396480491156"
  })
  @DisplayName("Should match reference contrast when foreground and background are specified")
  void shouldMatchReferenceContrastWhenForegroundAndBackgroundAreSpecified(
      int foreground, int background, double expected) {
    assertThat(WcagContrast.ratio(foreground, background))
        .as("independent oracle")
        .isCloseTo(expected, within(1e-12));
    assertThat(ColorContrast.contrastRatio(foreground, background))
        .isCloseTo(expected, within(1e-12));
  }

  @ParameterizedTest(name = "alpha {0}: RGB {1}")
  @CsvSource({"0, 0x071F1B", "128, 0x838F8D", "255, 0xFFFFFF"})
  @DisplayName("Should composite each channel when opacity includes endpoints and a partial wash")
  void shouldCompositeEachChannelWhenOpacityIncludesEndpointsAndPartialWash(
      int alpha, int expected) {
    assertThat(ColorContrast.composite(0xFFFFFF, alpha, 0x071F1B)).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should preserve colored channel contributions when compositing a partial foreground")
  void shouldPreserveColoredChannelContributionsWhenCompositingPartialForeground() {
    assertThat(ColorContrast.composite(0xE03080, 64, 0x1040A0)).isEqualTo(0x443B97);
  }

  @ParameterizedTest(name = "foreground {0}, background {1}, floor {2}")
  @CsvSource({"0xFFFFFF, 0x808080, 4.5", "0x000000, 0x808080, 7.0"})
  @DisplayName("Should report unavailable opacity when opaque text cannot meet contrast")
  void shouldReportUnavailableOpacityWhenOpaqueTextCannotMeetContrast(
      int foreground, int background, float floor) {
    assertThat(ColorContrast.minimumAlpha(foreground, background, floor)).isEmpty();
  }

  @ParameterizedTest(name = "foreground {0}, background {1}, minimum alpha {2}")
  @CsvSource({
    "0xFFFFFF, 0x000000, 117",
    "0x000000, 0xFFFFFF, 137",
    "0xFFFFFF, 0x071F1B, 118",
    "0x000000, 0xE9B658, 150"
  })
  @DisplayName("Should choose the smallest passing opacity when text needs body contrast")
  void shouldChooseSmallestPassingOpacityWhenTextNeedsBodyContrast(
      int foreground, int background, int expectedAlpha) {
    assertThat(ColorContrast.minimumAlpha(foreground, background, 4.5f)).hasValue(expectedAlpha);
    assertThat(
            WcagContrast.ratio(
                ColorContrast.composite(foreground, expectedAlpha, background), background))
        .isGreaterThanOrEqualTo(4.5);
    assertThat(
            WcagContrast.ratio(
                ColorContrast.composite(foreground, expectedAlpha - 1, background), background))
        .isLessThan(4.5);
  }

  @ParameterizedTest(name = "background {0}: text {1}")
  @CsvSource({
    "0x071F1B, 0xFFFFFF",
    "0xE9B658, 0x000000",
    "0x767676, 0xFFFFFF",
    "0x777777, 0x000000",
    "0x808080, 0x000000",
    "0x0000FF, 0xFFFFFF",
    "0x00FF00, 0x000000"
  })
  @DisplayName("Should prefer white only when it meets body contrast on the background")
  void shouldPreferWhiteOnlyWhenItMeetsBodyContrastOnBackground(int background, int expectedText) {
    assertThat(ColorContrast.contrastingTextColor(background)).isEqualTo(expectedText);
    assertThat(WcagContrast.ratio(expectedText, background)).isGreaterThanOrEqualTo(4.5);
  }
}
