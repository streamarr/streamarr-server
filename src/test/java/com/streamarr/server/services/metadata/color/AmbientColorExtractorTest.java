package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Ambient Color Extractor Tests")
class AmbientColorExtractorTest {

  private static final int OPAQUE = 0xFF000000;
  private static final int RED = OPAQUE | 0xFF0000;
  private static final int GREEN = OPAQUE | 0x00FF00;
  private static final int BLUE = OPAQUE | 0x0000FF;
  private static final int YELLOW = OPAQUE | 0xFFFF00;
  private static final int WHITE = OPAQUE | 0xFFFFFF;
  private static final int BLACK = OPAQUE;
  private static final int TRANSPARENT = 0x00000000;

  private static final int GRAY = OPAQUE | 0x808080;
  private static final int TEAL = OPAQUE | 0x00A0A0;
  private static final int MAGENTA = OPAQUE | 0xA000A0;
  private static final int PURPLE = OPAQUE | 0x8000A0;
  private static final int SKIN_TONE = OPAQUE | 0xC88868;
  private static final int NEAR_WHITE = OPAQUE | 0xF8F8F8;
  private static final int NEAR_BLACK = OPAQUE | 0x080808;
  private static final int PASTEL_LILAC = OPAQUE | 0xC0A0C0;
  private static final int PASTEL_MINT = OPAQUE | 0xA0C0A0;
  private static final int DESATURATED_BLUE = OPAQUE | 0x4080C0;

  @Test
  @DisplayName("Should extract quadrant averages when corners are distinct")
  void shouldExtractQuadrantAveragesWhenCornersAreDistinct() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 50, 50), RED)
            .paint(new Rectangle(50, 0, 50, 50), GREEN)
            .paint(new Rectangle(0, 50, 50, 50), BLUE)
            .paint(new Rectangle(50, 50, 50, 50), YELLOW)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.topLeft()).isEqualTo("#ff0000");
    assertThat(colors.topRight()).isEqualTo("#00ff00");
    assertThat(colors.bottomLeft()).isEqualTo("#0000ff");
    assertThat(colors.bottomRight()).isEqualTo("#ffff00");
  }

  @Test
  @DisplayName("Should average in linear light when corner mixes black and white")
  void shouldAverageInLinearLightWhenCornerMixesBlackAndWhite() {
    var image =
        ArtworkCanvas.size(100, 100)
            .fill(RED)
            .checkerboard(new Rectangle(0, 0, 50, 50), BLACK, WHITE)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.topLeft()).isEqualTo("#bcbcbc");
  }

  @Test
  @DisplayName("Should ignore translucent pixels when averaging corner")
  void shouldIgnoreTranslucentPixelsWhenAveragingCorner() {
    var translucentRed = 0x7C000000 | 0xFF0000;
    var barelyOpaqueBlue = 0x7D000000 | 0x0000FF;
    var image =
        ArtworkCanvas.size(100, 100)
            .fill(GREEN)
            .paint(new Rectangle(0, 0, 25, 50), translucentRed)
            .paint(new Rectangle(25, 0, 25, 50), barelyOpaqueBlue)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.topLeft()).isEqualTo("#0000ff");
  }

  @Test
  @DisplayName("Should fall back to whole-image average when corner has no opaque pixels")
  void shouldFallBackToWholeImageAverageWhenCornerHasNoOpaquePixels() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 50, 50), RED)
            .paint(new Rectangle(0, 50, 50, 50), BLUE)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.topLeft()).isEqualTo("#ff0000");
    assertThat(colors.bottomLeft()).isEqualTo("#0000ff");
    assertThat(colors.topRight()).isEqualTo("#bc00bc");
    assertThat(colors.bottomRight()).isEqualTo("#bc00bc");
  }

  @Test
  @DisplayName("Should return empty when image is fully transparent")
  void shouldReturnEmptyWhenImageIsFullyTransparent() {
    var image = ArtworkCanvas.size(100, 100).fill(TRANSPARENT).image();

    assertThat(AmbientColorExtractor.extract(image)).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when opaque coverage is below threshold")
  void shouldReturnEmptyWhenOpaqueCoverageIsBelowThreshold() {
    var image = ArtworkCanvas.size(100, 100).paint(new Rectangle(0, 0, 5, 100), RED).image();

    assertThat(AmbientColorExtractor.extract(image)).isEmpty();
  }

  @Test
  @DisplayName("Should extract colors when opaque coverage meets threshold")
  void shouldExtractColorsWhenOpaqueCoverageMeetsThreshold() {
    var image = ArtworkCanvas.size(100, 100).paint(new Rectangle(0, 0, 10, 100), RED).image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#f80000");
  }

  @Test
  @DisplayName("Should pick vibrant color as primary when neutral dominates")
  void shouldPickVibrantColorAsPrimaryWhenNeutralDominates() {
    var image =
        ArtworkCanvas.size(200, 200)
            .paint(new Rectangle(0, 0, 160, 200), GRAY)
            .paint(new Rectangle(160, 0, 40, 200), TEAL)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#00a0a0");
  }

  @Test
  @DisplayName("Should exclude skin tones when selecting primary")
  void shouldExcludeSkinTonesWhenSelectingPrimary() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 80, 100), SKIN_TONE)
            .paint(new Rectangle(80, 0, 20, 100), PURPLE)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#8000a0");
  }

  @Test
  @DisplayName("Should exclude near-white and near-black when selecting primary")
  void shouldExcludeNearWhiteAndNearBlackWhenSelectingPrimary() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 60, 100), NEAR_WHITE)
            .paint(new Rectangle(60, 0, 25, 100), NEAR_BLACK)
            .paint(new Rectangle(85, 0, 15, 100), TEAL)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#00a0a0");
  }

  @Test
  @DisplayName("Should keep vivid orange when saturation exceeds skin-tone threshold")
  void shouldKeepVividOrangeWhenSaturationExceedsSkinToneThreshold() {
    var vividOrange = OPAQUE | 0xF88000;
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 80, 100), GRAY)
            .paint(new Rectangle(80, 0, 20, 100), vividOrange)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#f88000");
  }

  @Test
  @DisplayName("Should fall back to dominant when saturated colors are outside lightness range")
  void shouldFallBackToDominantWhenSaturatedColorsAreOutsideLightnessRange() {
    var darkTeal = OPAQUE | 0x002020;
    var lightPink = OPAQUE | 0xF8A0A0;
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 60, 100), darkTeal)
            .paint(new Rectangle(60, 0, 40, 100), lightPink)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#002020");
  }

  @Test
  @DisplayName("Should fall back to dominant swatch when no swatch is vibrant enough")
  void shouldFallBackToDominantSwatchWhenNoSwatchIsVibrantEnough() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 70, 100), PASTEL_LILAC)
            .paint(new Rectangle(70, 0, 30, 100), PASTEL_MINT)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#c0a0c0");
  }

  @Test
  @DisplayName("Should fall back to unfiltered dominant when all colors are filtered")
  void shouldFallBackToUnfilteredDominantWhenAllColorsAreFiltered() {
    var image = ArtworkCanvas.size(100, 100).fill(NEAR_WHITE).image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#f8f8f8");
  }

  @Test
  @DisplayName("Should prefer higher population when vibrant candidates tie")
  void shouldPreferHigherPopulationWhenVibrantCandidatesTie() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 55, 100), GRAY)
            .paint(new Rectangle(55, 0, 30, 100), TEAL)
            .paint(new Rectangle(85, 0, 15, 100), MAGENTA)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#00a0a0");
  }

  @Test
  @DisplayName("Should prefer saturation and lightness near targets when populations are equal")
  void shouldPreferSaturationAndLightnessNearTargetsWhenPopulationsAreEqual() {
    var image =
        ArtworkCanvas.size(100, 100)
            .paint(new Rectangle(0, 0, 50, 100), GRAY)
            .paint(new Rectangle(50, 0, 25, 100), DESATURATED_BLUE)
            .paint(new Rectangle(75, 0, 25, 100), TEAL)
            .image();

    var colors = AmbientColorExtractor.extract(image).orElseThrow();

    assertThat(colors.primary()).isEqualTo("#00a0a0");
  }

  private static final class ArtworkCanvas {

    private final BufferedImage image;

    private ArtworkCanvas(BufferedImage image) {
      this.image = image;
    }

    static ArtworkCanvas size(int width, int height) {
      return new ArtworkCanvas(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }

    ArtworkCanvas fill(int argb) {
      return paint(new Rectangle(0, 0, image.getWidth(), image.getHeight()), argb);
    }

    ArtworkCanvas paint(Rectangle region, int argb) {
      for (var y = region.y; y < region.y + region.height; y++) {
        for (var x = region.x; x < region.x + region.width; x++) {
          image.setRGB(x, y, argb);
        }
      }
      return this;
    }

    ArtworkCanvas checkerboard(Rectangle region, int evenArgb, int oddArgb) {
      for (var y = region.y; y < region.y + region.height; y++) {
        for (var x = region.x; x < region.x + region.width; x++) {
          image.setRGB(x, y, (x + y) % 2 == 0 ? evenArgb : oddArgb);
        }
      }
      return this;
    }

    BufferedImage image() {
      return image;
    }
  }
}
