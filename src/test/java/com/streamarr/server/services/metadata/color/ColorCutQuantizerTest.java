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

  private static int[] colorRun(int rgb, int count) {
    var run = new int[count];
    Arrays.fill(run, rgb);
    return run;
  }

  private static int[] concat(int[]... runs) {
    return Arrays.stream(runs).flatMapToInt(Arrays::stream).toArray();
  }
}
