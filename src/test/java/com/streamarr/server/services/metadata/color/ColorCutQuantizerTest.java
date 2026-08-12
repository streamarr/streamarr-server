package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Color Cut Quantizer Tests")
class ColorCutQuantizerTest {

  @Test
  @DisplayName("Should return exact colors when distinct count within limit")
  void shouldReturnExactColorsWhenDistinctCountWithinLimit() {
    var pixels = concat(colorRun(0xF80000, 5), colorRun(0x00F800, 3), colorRun(0x0000F8, 2));

    var swatches = new ColorCutQuantizer(pixels, 16, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches)
        .containsExactlyInAnyOrder(
            new Swatch(0xF80000, 5), new Swatch(0x00F800, 3), new Swatch(0x0000F8, 2));
  }

  @Test
  @DisplayName("Should reduce to limit when distinct count exceeds limit")
  void shouldReduceToLimitWhenDistinctCountExceedsLimit() {
    var pixels = new int[32];
    for (var i = 0; i < pixels.length; i++) {
      pixels[i] = (8 * i) << 16;
    }

    var swatches = new ColorCutQuantizer(pixels, 8, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches).hasSize(8);
    assertThat(swatches.stream().mapToInt(Swatch::population).sum()).isEqualTo(32);
  }

  @Test
  @DisplayName("Should split along green axis when green varies most")
  void shouldSplitAlongGreenAxisWhenGreenVariesMost() {
    var pixels = new int[32];
    for (var i = 0; i < pixels.length; i++) {
      pixels[i] = (8 * i) << 8;
    }

    var swatches = new ColorCutQuantizer(pixels, 8, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches).hasSize(8);
    assertThat(swatches)
        .allSatisfy(
            swatch -> {
              assertThat((swatch.rgb() >> 16) & 0xFF).isZero();
              assertThat(swatch.rgb() & 0xFF).isZero();
            });
  }

  @Test
  @DisplayName("Should split along blue axis when blue varies most")
  void shouldSplitAlongBlueAxisWhenBlueVariesMost() {
    var pixels = new int[32];
    for (var i = 0; i < pixels.length; i++) {
      pixels[i] = 8 * i;
    }

    var swatches = new ColorCutQuantizer(pixels, 8, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches).hasSize(8);
    assertThat(swatches)
        .allSatisfy(
            swatch -> {
              assertThat((swatch.rgb() >> 16) & 0xFF).isZero();
              assertThat((swatch.rgb() >> 8) & 0xFF).isZero();
            });
  }

  @Test
  @DisplayName("Should split along blue axis when red varies more than green")
  void shouldSplitAlongBlueAxisWhenRedVariesMoreThanGreen() {
    var pixels = concat(colorRun(0x000000, 1), colorRun(0x100850, 1), colorRun(0x0800F8, 1));

    var swatches = new ColorCutQuantizer(pixels, 2, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches)
        .containsExactlyInAnyOrder(new Swatch(0x000000, 1), new Swatch(0x1008A8, 2));
  }

  @Test
  @DisplayName("Should clamp split point when population concentrates in last color")
  void shouldClampSplitPointWhenPopulationConcentratesInLastColor() {
    var pixels = concat(colorRun(0x000000, 1), colorRun(0x080000, 1), colorRun(0xF80000, 6));

    var swatches = new ColorCutQuantizer(pixels, 2, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches)
        .containsExactlyInAnyOrder(new Swatch(0x080000, 2), new Swatch(0xF80000, 6));
  }

  @Test
  @DisplayName("Should average all colors when limit is one")
  void shouldAverageAllColorsWhenLimitIsOne() {
    var pixels = concat(colorRun(0x000000, 2), colorRun(0x404040, 2));

    var swatches = new ColorCutQuantizer(pixels, 1, SwatchFilter.ALLOW_ALL).getQuantizedColors();

    assertThat(swatches).containsExactly(new Swatch(0x202020, 4));
  }

  @Test
  @DisplayName("Should exclude filtered colors when building histogram")
  void shouldExcludeFilteredColorsWhenBuildingHistogram() {
    var pixels = concat(colorRun(0xF8F8F8, 5), colorRun(0x00A0A0, 5));

    var swatches = new ColorCutQuantizer(pixels, 16, SwatchFilter.DEFAULT).getQuantizedColors();

    assertThat(swatches).containsExactly(new Swatch(0x00A0A0, 5));
  }

  @Test
  @DisplayName("Should return no swatches when every color is filtered")
  void shouldReturnNoSwatchesWhenEveryColorIsFiltered() {
    var pixels = colorRun(0xF8F8F8, 5);

    var swatches = new ColorCutQuantizer(pixels, 16, SwatchFilter.DEFAULT).getQuantizedColors();

    assertThat(swatches).isEmpty();
  }

  @Test
  @DisplayName("Should drop averaged swatch when box average falls in excluded range")
  void shouldDropAveragedSwatchWhenBoxAverageFallsInExcludedRange() {
    var pixels = concat(colorRun(0xC86E64, 1), colorRun(0xC8AF64, 1));

    var swatches = new ColorCutQuantizer(pixels, 1, SwatchFilter.DEFAULT).getQuantizedColors();

    assertThat(swatches).isEmpty();
  }

  private static int[] colorRun(int rgb, int count) {
    var run = new int[count];
    Arrays.fill(run, rgb);
    return run;
  }

  private static int[] concat(int[]... runs) {
    return Arrays.stream(runs).flatMapToInt(Arrays::stream).toArray();
  }
}
