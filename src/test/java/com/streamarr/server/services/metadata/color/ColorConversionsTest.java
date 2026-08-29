package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Color Conversions Tests")
class ColorConversionsTest {

  @Test
  @DisplayName("Should parse packed rgb when hex is lowercase")
  void shouldParsePackedRgbWhenHexIsLowercase() {
    assertThat(ColorConversions.fromHex("#00a0a0")).isEqualTo(0x00A0A0);
  }

  @Test
  @DisplayName("Should parse packed rgb when hex is uppercase")
  void shouldParsePackedRgbWhenHexIsUppercase() {
    assertThat(ColorConversions.fromHex("#68F8F8")).isEqualTo(0x68F8F8);
  }

  @Test
  @DisplayName("Should round trip when hex is formatted then parsed")
  void shouldRoundTripWhenHexIsFormattedThenParsed() {
    assertThat(ColorConversions.toHex(ColorConversions.fromHex("#103070"))).isEqualTo("#103070");
  }

  @ParameterizedTest
  @ValueSource(ints = {0x00A0A0, 0x68F8F8, 0x103070, 0x283830, 0xC8D0C8, 0xE9B658, 0xFF0000})
  @DisplayName("Should round trip when rgb is converted to hsl and back")
  void shouldRoundTripWhenRgbIsConvertedToHslAndBack(int rgb) {
    assertThat(ColorConversions.hslToRgb(ColorConversions.rgbToHsl(rgb))).isEqualTo(rgb);
  }

  @Test
  @DisplayName("Should produce gray when saturation is zero")
  void shouldProduceGrayWhenSaturationIsZero() {
    assertThat(ColorConversions.hslToRgb(new float[] {0f, 0f, 0.5f})).isEqualTo(0x808080);
  }

  @Test
  @DisplayName("Should clamp to white and black when lightness hits the extremes")
  void shouldClampToWhiteAndBlackWhenLightnessHitsTheExtremes() {
    assertThat(ColorConversions.hslToRgb(new float[] {200f, 1f, 1f})).isEqualTo(0xFFFFFF);
    assertThat(ColorConversions.hslToRgb(new float[] {200f, 1f, 0f})).isEqualTo(0x000000);
  }
}
