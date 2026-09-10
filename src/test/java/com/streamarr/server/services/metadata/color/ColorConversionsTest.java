package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Color Conversions Tests")
class ColorConversionsTest {

  @ParameterizedTest(name = "hex {0}: packed RGB {1}")
  @CsvSource({
    "#000000, 0x000000",
    "#FFFFFF, 0xFFFFFF",
    "#00a0a0, 0x00A0A0",
    "#68F8F8, 0x68F8F8",
    "#Ab12cD, 0xAB12CD",
    "#00000f, 0x00000F"
  })
  @DisplayName("Should parse packed RGB when hex includes mixed case and leading zeros")
  void shouldParsePackedRgbWhenHexIncludesMixedCaseAndLeadingZeros(String hex, int expected) {
    assertThat(ColorConversions.fromHex(hex)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "packed RGB {0}: hex {1}")
  @CsvSource({"0x000000, #000000", "0xFFFFFF, #ffffff", "0x00000F, #00000f", "0xAB12CD, #ab12cd"})
  @DisplayName("Should emit six lowercase digits when formatting packed RGB")
  void shouldEmitSixLowercaseDigitsWhenFormattingPackedRgb(int rgb, String expected) {
    assertThat(ColorConversions.toHex(rgb)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "hue {0}: RGB {1}")
  @CsvSource({
    "0, 0xFF0000",
    "30, 0xFF8000",
    "60, 0xFFFF00",
    "90, 0x80FF00",
    "120, 0x00FF00",
    "150, 0x00FF80",
    "180, 0x00FFFF",
    "210, 0x0080FF",
    "240, 0x0000FF",
    "270, 0x8000FF",
    "300, 0xFF00FF",
    "330, 0xFF0080"
  })
  @DisplayName("Should match the RGB color wheel when hue crosses every HSL sector")
  void shouldMatchRgbColorWheelWhenHueCrossesEveryHslSector(float hue, int expected) {
    assertThat(ColorConversions.hslToRgb(new float[] {hue, 1f, 0.5f})).isEqualTo(expected);
  }

  @ParameterizedTest(name = "lightness {0}: gray {1}")
  @CsvSource({"0, 0x000000", "0.5, 0x808080", "1, 0xFFFFFF"})
  @DisplayName("Should ignore hue when saturation is zero")
  void shouldIgnoreHueWhenSaturationIsZero(float lightness, int expected) {
    assertThat(ColorConversions.hslToRgb(new float[] {210f, 0f, lightness})).isEqualTo(expected);
  }

  @ParameterizedTest(name = "lightness {0}: RGB {1}")
  @CsvSource({"0, 0x000000", "1, 0xFFFFFF"})
  @DisplayName("Should emit black or white when saturated lightness reaches an endpoint")
  void shouldEmitBlackOrWhiteWhenSaturatedLightnessReachesEndpoint(float lightness, int expected) {
    assertThat(ColorConversions.hslToRgb(new float[] {200f, 1f, lightness})).isEqualTo(expected);
  }
}
