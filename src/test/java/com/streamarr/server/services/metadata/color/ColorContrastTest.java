package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Color Contrast Tests")
class ColorContrastTest {

  private static final int WHITE = 0xFFFFFF;
  private static final int BLACK = 0x000000;
  private static final int MID_GRAY = 0x808080;
  private static final int TEAL_BASE = 0x071F1B;
  private static final int AMBER_FIELD = 0xE9B658;

  @Test
  @DisplayName("Should return full luminance when color is white")
  void shouldReturnFullLuminanceWhenColorIsWhite() {
    assertThat(ColorContrast.relativeLuminance(WHITE)).isCloseTo(1.0, within(1e-9));
  }

  @Test
  @DisplayName("Should return zero luminance when color is black")
  void shouldReturnZeroLuminanceWhenColorIsBlack() {
    assertThat(ColorContrast.relativeLuminance(BLACK)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("Should linearize channels when color is mid gray")
  void shouldLinearizeChannelsWhenColorIsMidGray() {
    assertThat(ColorContrast.relativeLuminance(MID_GRAY))
        .as("sRGB #808080 is about 21.6% linear light, not 50%")
        .isCloseTo(0.2159, within(0.0005));
  }

  @Test
  @DisplayName("Should return maximum ratio when contrasting white with black")
  void shouldReturnMaximumRatioWhenContrastingWhiteWithBlack() {
    assertThat(ColorContrast.contrastRatio(WHITE, BLACK)).isCloseTo(21.0, within(1e-9));
  }

  @Test
  @DisplayName("Should be symmetric when foreground and background swap")
  void shouldBeSymmetricWhenForegroundAndBackgroundSwap() {
    assertThat(ColorContrast.contrastRatio(BLACK, WHITE))
        .isEqualTo(ColorContrast.contrastRatio(WHITE, BLACK));
  }

  @Test
  @DisplayName("Should return unit ratio when colors are identical")
  void shouldReturnUnitRatioWhenColorsAreIdentical() {
    assertThat(ColorContrast.contrastRatio(MID_GRAY, MID_GRAY)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should return the foreground when composited fully opaque")
  void shouldReturnForegroundWhenCompositedFullyOpaque() {
    assertThat(ColorContrast.composite(WHITE, 255, TEAL_BASE)).isEqualTo(WHITE);
  }

  @Test
  @DisplayName("Should return the background when composited fully transparent")
  void shouldReturnBackgroundWhenCompositedFullyTransparent() {
    assertThat(ColorContrast.composite(WHITE, 0, TEAL_BASE)).isEqualTo(TEAL_BASE);
  }

  @Test
  @DisplayName("Should return no alpha when opaque foreground cannot reach contrast")
  void shouldReturnNoAlphaWhenOpaqueForegroundCannotReachContrast() {
    assertThat(ColorContrast.minimumAlpha(WHITE, MID_GRAY, 4.5f)).isEmpty();
  }

  @Test
  @DisplayName("Should find the lowest passing alpha when opaque foreground exceeds contrast")
  void shouldFindLowestPassingAlphaWhenOpaqueForegroundExceedsContrast() {
    var alpha = ColorContrast.minimumAlpha(WHITE, BLACK, 4.5f).orElseThrow();

    assertThat(alpha).isLessThan(255);
    assertThat(ColorContrast.contrastRatio(ColorContrast.composite(WHITE, alpha, BLACK), BLACK))
        .isGreaterThanOrEqualTo(4.5);
    assertThat(ColorContrast.contrastRatio(ColorContrast.composite(WHITE, alpha - 1, BLACK), BLACK))
        .isLessThan(4.5);
  }

  @Test
  @DisplayName("Should choose white text when background is dark")
  void shouldChooseWhiteTextWhenBackgroundIsDark() {
    assertThat(ColorContrast.contrastingTextColor(TEAL_BASE)).isEqualTo(WHITE);
  }

  @Test
  @DisplayName("Should choose black text when background is bright")
  void shouldChooseBlackTextWhenBackgroundIsBright() {
    assertThat(ColorContrast.contrastingTextColor(AMBER_FIELD)).isEqualTo(BLACK);
  }

  @Test
  @DisplayName("Should choose black text when white falls just short of body contrast")
  void shouldChooseBlackTextWhenWhiteFallsJustShortOfBodyContrast() {
    assertThat(ColorContrast.contrastingTextColor(MID_GRAY))
        .as("white on #808080 is 3.95:1, under the 4.5:1 body floor")
        .isEqualTo(BLACK);
  }

  @Test
  @DisplayName("Should prefer white text when it just clears body contrast")
  void shouldPreferWhiteTextWhenItJustClearsBodyContrast() {
    assertThat(ColorContrast.contrastingTextColor(0x767676))
        .as("white on #767676 is 4.54:1")
        .isEqualTo(WHITE);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        0x000000, 0x1A1A1A, 0x404040, 0x5C5C5C, 0x767676, 0x808080, 0x8A8A8A, 0xA0A0A0, 0xC0C0C0,
        0xFFFFFF
      })
  @DisplayName("Should reach body contrast when gray level varies")
  void shouldReachBodyContrastWhenGrayLevelVaries(int background) {
    var text = ColorContrast.contrastingTextColor(background);

    assertThat(ColorContrast.contrastRatio(text, background))
        .as("white and black ratios multiply to 21, so one always clears 4.5")
        .isGreaterThanOrEqualTo(4.5);
  }
}
