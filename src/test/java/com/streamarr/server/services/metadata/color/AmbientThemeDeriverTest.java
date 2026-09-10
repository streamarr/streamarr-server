package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.AmbientTheme;
import com.streamarr.server.support.WcagContrast;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Ambient Theme Deriver Tests")
class AmbientThemeDeriverTest {

  private static final String TEAL_BACKDROP = "#0d322c";
  private static final String TEAL_PANEL = "#0e3b34";
  private static final String TEAL_SELECTED = "#1f6b5a";
  private static final String MINT = "#6fe0bf";

  private static final String AMBER_FIELD = "#e9b658";
  private static final String AMBER_BRIGHT = "#f0c069";
  private static final String SAND = "#d9c5a5";
  private static final String UMBER = "#6b3a10";

  @ParameterizedTest(name = "corner {0}, bright family {1}")
  @CsvSource({
    "0, false, #0e3b34", "1, false, #0e3b34", "2, false, #0e3b34", "3, false, #0e3b34",
    "0, true, #d9c5a5", "1, true, #d9c5a5", "2, true, #d9c5a5", "3, true, #d9c5a5"
  })
  @DisplayName("Should choose the family from all corners when one corner changes the mean")
  void shouldChooseFamilyFromAllCornersWhenOneCornerChangesMean(
      int corner, boolean bright, String expectedBase) {
    var field = new String[4];
    Arrays.fill(field, bright ? "#a9a9a9" : "#aaaaaa");
    field[corner] = bright ? "#ffffff" : "#000000";
    var colors =
        tealColors()
            .lightMuted(SAND)
            .topLeft(field[0])
            .topRight(field[1])
            .bottomRight(field[2])
            .bottomLeft(field[3])
            .build();

    assertThat(AmbientThemeDeriver.derive(colors).base()).isEqualTo(expectedBase);
  }

  @Test
  @DisplayName("Should distinguish the accent from the background when artwork is black")
  void shouldDistinguishAccentFromBackgroundWhenArtworkIsBlack() {
    var artwork = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    var colors = AmbientColorExtractor.extract(artwork).orElseThrow();

    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(contrast(theme.accent(), theme.base())).isGreaterThanOrEqualTo(3.0);
    assertThat(theme.selected()).isNotEqualTo(theme.base());
  }

  @Test
  @DisplayName("Should keep selected text readable when a dark vibrant swatch is bright green")
  void shouldKeepSelectedTextReadableWhenDarkVibrantSwatchIsBrightGreen() {
    var artwork = new BufferedImage(10, 1, BufferedImage.TYPE_INT_RGB);
    for (var x = 0; x < artwork.getWidth(); x++) {
      var color =
          switch (x) {
            case 9 -> 0x00E000;
            case 6, 7, 8 -> 0xF80000;
            default -> 0x283830;
          };
      artwork.setRGB(x, 0, color);
    }

    var colors = AmbientColorExtractor.extract(artwork).orElseThrow();
    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(contrast(theme.textPrimary(), theme.selected())).isGreaterThanOrEqualTo(4.5);
    assertThat(contrast(theme.textSecondary(), theme.selected())).isGreaterThanOrEqualTo(3.0);
  }

  @Test
  @DisplayName("Should keep panel text readable when base is near the light text contrast limit")
  void shouldKeepPanelTextReadableWhenBaseIsNearLightTextContrastLimit() {
    var colors = tealColors().darkMuted("#707070").darkVibrant("#103070").build();

    var theme = AmbientThemeDeriver.derive(colors);

    for (var surface :
        Map.of("base", theme.base(), "panel", theme.panel(), "selected", theme.selected())
            .entrySet()) {
      assertThat(contrast(theme.textPrimary(), surface.getValue()))
          .as("primary on %s", surface.getKey())
          .isGreaterThanOrEqualTo(4.5);
      assertThat(contrast(theme.textSecondary(), surface.getValue()))
          .as("secondary on %s", surface.getKey())
          .isGreaterThanOrEqualTo(3.0);
    }
  }

  @Test
  @DisplayName("Should keep dark text readable when bright artwork has a blue selection")
  void shouldKeepDarkTextReadableWhenBrightArtworkHasBlueSelection() {
    var colors = amberColors().lightVibrant("#4040f8").build();

    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(contrast(theme.textPrimary(), theme.selected())).isGreaterThanOrEqualTo(4.5);
    assertThat(contrast(theme.textSecondary(), theme.selected())).isGreaterThanOrEqualTo(3.0);
    assertThat(contrast(theme.textPrimary(), theme.base())).isGreaterThanOrEqualTo(4.5);
  }

  @Test
  @DisplayName("Should build a dark theme from the dark muted swatch when corners are dark")
  void shouldBuildDarkThemeFromDarkMutedSwatchWhenCornersAreDark() {
    var theme = AmbientThemeDeriver.derive(tealColors().build());

    assertThat(theme)
        .isEqualTo(
            AmbientTheme.builder()
                .base(TEAL_PANEL)
                .panel("#1f4842")
                .selected(TEAL_SELECTED)
                .accent(MINT)
                .onAccent("#08110e")
                .textPrimary("#edf3f2")
                .textSecondary("#97bab2")
                .build());
  }

  @Test
  @DisplayName("Should build a bright theme from the light muted swatch when corners are bright")
  void shouldBuildBrightThemeFromLightMutedSwatchWhenCornersAreBright() {
    var theme = AmbientThemeDeriver.derive(amberColors().build());

    assertThat(theme)
        .isEqualTo(
            AmbientTheme.builder()
                .base(SAND)
                .panel("#c9b799")
                .selected(AMBER_BRIGHT)
                .accent(UMBER)
                .onAccent("#f3efec")
                .textPrimary("#0f0e0c")
                .textSecondary("#645b4c")
                .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("themeExamples")
  @DisplayName("Should clear text contrast floors when theme is dark or bright")
  void shouldClearTextContrastFloorsWhenThemeIsDarkOrBright(AmbientColors colors) {
    var theme = AmbientThemeDeriver.derive(colors);

    for (var surface :
        Map.of("base", theme.base(), "panel", theme.panel(), "selected", theme.selected())
            .entrySet()) {
      assertThat(contrast(theme.textPrimary(), surface.getValue()))
          .as("primary on %s", surface.getKey())
          .isGreaterThanOrEqualTo(4.5);
      assertThat(contrast(theme.textSecondary(), surface.getValue()))
          .as("secondary on %s", surface.getKey())
          .isGreaterThanOrEqualTo(3.0);
    }

    assertThat(contrast(theme.onAccent(), theme.accent()))
        .as("on accent")
        .isGreaterThanOrEqualTo(4.5);
    assertThat(contrast(theme.accent(), theme.base()))
        .as("accent on base")
        .isGreaterThanOrEqualTo(3.0);
  }

  private static Stream<Arguments> themeExamples() {
    return Stream.of(
        Arguments.of(Named.of("dark", tealColors().build())),
        Arguments.of(Named.of("bright", amberColors().build())));
  }

  @Test
  @DisplayName("Should keep the dark family when corners are dark but primary is bright")
  void shouldKeepDarkFamilyWhenCornersAreDarkButPrimaryIsBright() {
    var colors = tealColors().primary(AMBER_BRIGHT).lightMuted(SAND).build();

    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(theme.base())
        .as("family follows the artwork field, not the accent")
        .isEqualTo(TEAL_PANEL);
  }

  @Test
  @DisplayName("Should switch to the light family when mean corner luminance reaches the threshold")
  void shouldSwitchToLightFamilyWhenMeanCornerLuminanceReachesThreshold() {
    var justLight = tealColors().lightMuted(SAND);
    corners(justLight, "#aaaaaa");
    var justDark = tealColors().lightMuted(SAND);
    corners(justDark, "#a9a9a9");

    assertThat(AmbientThemeDeriver.derive(justLight.build()).base()).isEqualTo(SAND);
    assertThat(AmbientThemeDeriver.derive(justDark.build()).base()).isEqualTo(TEAL_PANEL);
  }

  @Test
  @DisplayName("Should fall back to the dark vibrant swatch when dark muted is absent")
  void shouldFallBackToDarkVibrantSwatchWhenDarkMutedIsAbsent() {
    var theme = AmbientThemeDeriver.derive(tealColors().darkMuted(null).build());

    assertThat(theme.base()).isEqualTo(TEAL_SELECTED);
    assertThat(theme.selected())
        .as("selected must not collapse onto base")
        .isNotEqualTo(theme.base());
  }

  @Test
  @DisplayName("Should darken the primary color for base when no dark swatch exists")
  void shouldDarkenPrimaryColorForBaseWhenNoDarkSwatchExists() {
    var theme = AmbientThemeDeriver.derive(tealColors().darkMuted(null).darkVibrant(null).build());

    assertThat(theme.base()).isEqualTo("#176d54");
  }

  @Test
  @DisplayName("Should lighten the primary color for base when no light swatch exists")
  void shouldLightenPrimaryColorForBaseWhenNoLightSwatchExists() {
    var theme =
        AmbientThemeDeriver.derive(amberColors().lightMuted(null).lightVibrant(null).build());

    assertThat(theme.base()).isEqualTo("#f0cc8a");
  }

  @Test
  @DisplayName("Should use the light vibrant base when bright artwork has no light muted swatch")
  void shouldUseLightVibrantBaseWhenBrightArtworkHasNoLightMutedSwatch() {
    var colors = amberColors().lightMuted(null).build();

    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(theme.base()).isEqualTo(AMBER_BRIGHT);
    assertThat(theme.selected()).isNotEqualTo(theme.base());
  }

  @Test
  @DisplayName(
      "Should darken the primary color for accent when no dark swatch exists in a bright theme")
  void shouldDarkenPrimaryColorForAccentWhenNoDarkSwatchExistsInBrightTheme() {
    var theme = AmbientThemeDeriver.derive(amberColors().darkVibrant(null).build());

    assertThat(theme.accent()).isEqualTo("#75510f");
    assertThat(contrast(theme.accent(), theme.base())).isGreaterThanOrEqualTo(3.0);
  }

  @Test
  @DisplayName("Should use the dark muted accent when bright artwork has no dark vibrant swatch")
  void shouldUseDarkMutedAccentWhenBrightArtworkHasNoDarkVibrantSwatch() {
    var colors = amberColors().darkVibrant(null).darkMuted("#503830").build();

    assertThat(AmbientThemeDeriver.derive(colors).accent()).isEqualTo("#503830");
  }

  @Test
  @DisplayName("Should lift the accent when the primary color cannot stand out from base")
  void shouldLiftAccentWhenPrimaryColorCannotStandOutFromBase() {
    var navy = "#202080";
    var colors = tealColors().darkMuted("#101820").darkVibrant(null).primary(navy).build();

    var theme = AmbientThemeDeriver.derive(colors);

    assertThat(theme.accent()).isNotEqualTo(navy);
    assertThat(contrast(theme.accent(), theme.base())).isGreaterThanOrEqualTo(3.0);
    var accent = ColorConversions.rgbToHsl(ColorConversions.fromHex(theme.accent()));
    var original = ColorConversions.rgbToHsl(ColorConversions.fromHex(navy));
    assertThat(accent[0]).as("only lightness moves").isCloseTo(original[0], within(2f));
  }

  @Test
  @DisplayName("Should mix accent into base for selected when no vibrant swatch exists")
  void shouldMixAccentIntoBaseForSelectedWhenNoVibrantSwatchExists() {
    var theme = AmbientThemeDeriver.derive(tealColors().darkVibrant(null).build());

    assertThat(theme.selected()).isEqualTo("#2b6c5d");
  }

  @Test
  @DisplayName("Should preserve the accent mixture when a bright theme has no light vibrant swatch")
  void shouldPreserveAccentMixtureWhenBrightThemeHasNoLightVibrantSwatch() {
    var theme = AmbientThemeDeriver.derive(amberColors().lightVibrant(null).build());

    assertThat(theme.selected()).isEqualTo("#b79b78");
  }

  @Test
  @DisplayName("Should keep dark panel text readable when the base barely admits black text")
  void shouldKeepDarkPanelTextReadableWhenBaseBarelyAdmitsBlackText() {
    var theme = AmbientThemeDeriver.derive(amberColors().lightMuted("#777777").build());

    assertThat(theme.base()).isEqualTo("#777777");
    assertThat(contrast(theme.textPrimary(), theme.panel())).isGreaterThanOrEqualTo(4.5);
    assertThat(contrast(theme.textSecondary(), theme.panel())).isGreaterThanOrEqualTo(3.0);
    assertThat(theme.panel()).isNotEqualTo(theme.base());
  }

  private static AmbientColors.AmbientColorsBuilder tealColors() {
    var builder =
        AmbientColors.builder().primary(MINT).darkMuted(TEAL_PANEL).darkVibrant(TEAL_SELECTED);
    corners(builder, TEAL_BACKDROP);
    return builder;
  }

  private static AmbientColors.AmbientColorsBuilder amberColors() {
    var builder =
        AmbientColors.builder()
            .primary(AMBER_FIELD)
            .lightMuted(SAND)
            .lightVibrant(AMBER_BRIGHT)
            .darkVibrant(UMBER);
    corners(builder, AMBER_FIELD);
    return builder;
  }

  private static void corners(AmbientColors.AmbientColorsBuilder builder, String hex) {
    builder.topLeft(hex).topRight(hex).bottomRight(hex).bottomLeft(hex);
  }

  private static double contrast(String foreground, String background) {
    return WcagContrast.ratio(foreground, background);
  }
}
