package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.AmbientTheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("Should build a dark theme from the dark muted swatch when corners are dark")
  void shouldBuildDarkThemeFromDarkMutedSwatchWhenCornersAreDark() {
    var theme = AmbientThemeDeriver.derive(tealColors().build());

    assertThat(theme.base()).isEqualTo(TEAL_PANEL);
    assertThat(theme.accent()).isEqualTo(MINT);
    assertThat(theme.selected()).isEqualTo(TEAL_SELECTED);
    assertThat(luminance(theme.textPrimary())).isGreaterThan(luminance(theme.base()));
    assertThat(luminance(theme.panel())).isGreaterThan(luminance(theme.base()));
    assertThat(luminance(theme.onAccent())).isLessThan(luminance(theme.accent()));
  }

  @Test
  @DisplayName("Should build a bright theme from the light muted swatch when corners are bright")
  void shouldBuildBrightThemeFromLightMutedSwatchWhenCornersAreBright() {
    var theme = AmbientThemeDeriver.derive(amberColors().build());

    assertThat(theme.base()).isEqualTo(SAND);
    assertThat(theme.accent()).as("a dark button on the bright field").isEqualTo(UMBER);
    assertThat(theme.selected()).isEqualTo(AMBER_BRIGHT);
    assertThat(luminance(theme.textPrimary())).isLessThan(luminance(theme.base()));
    assertThat(luminance(theme.panel())).isLessThan(luminance(theme.base()));
    assertThat(luminance(theme.onAccent())).isGreaterThan(luminance(theme.accent()));
  }

  @Test
  @DisplayName("Should clear text contrast floors when theme is dark or bright")
  void shouldClearTextContrastFloorsWhenThemeIsDarkOrBright() {
    for (var theme :
        new AmbientTheme[] {
          AmbientThemeDeriver.derive(tealColors().build()),
          AmbientThemeDeriver.derive(amberColors().build())
        }) {
      assertThat(contrast(theme.textPrimary(), theme.base())).isGreaterThanOrEqualTo(4.5);
      assertThat(contrast(theme.textSecondary(), theme.base())).isGreaterThanOrEqualTo(3.0);
      assertThat(contrast(theme.onAccent(), theme.accent())).isGreaterThanOrEqualTo(4.5);
      assertThat(contrast(theme.accent(), theme.base())).isGreaterThanOrEqualTo(3.0);
    }
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

    var base = ColorConversions.rgbToHsl(ColorConversions.fromHex(theme.base()));
    var primary = ColorConversions.rgbToHsl(ColorConversions.fromHex(MINT));
    assertThat(base[2]).isCloseTo(0.26f, within(0.02f));
    assertThat(base[0]).isCloseTo(primary[0], within(2f));
  }

  @Test
  @DisplayName("Should lighten the primary color for base when no light swatch exists")
  void shouldLightenPrimaryColorForBaseWhenNoLightSwatchExists() {
    var theme =
        AmbientThemeDeriver.derive(amberColors().lightMuted(null).lightVibrant(null).build());

    var base = ColorConversions.rgbToHsl(ColorConversions.fromHex(theme.base()));
    assertThat(base[2]).isCloseTo(0.74f, within(0.02f));
  }

  @Test
  @DisplayName(
      "Should darken the primary color for accent when no dark swatch exists in a bright theme")
  void shouldDarkenPrimaryColorForAccentWhenNoDarkSwatchExistsInBrightTheme() {
    var theme = AmbientThemeDeriver.derive(amberColors().darkVibrant(null).build());

    assertThat(luminance(theme.accent())).isLessThan(luminance(theme.base()));
    assertThat(contrast(theme.accent(), theme.base())).isGreaterThanOrEqualTo(3.0);
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

    assertThat(luminance(theme.selected()))
        .isGreaterThan(luminance(theme.base()))
        .isLessThan(luminance(theme.accent()));
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

  private static double luminance(String hex) {
    return ColorContrast.relativeLuminance(ColorConversions.fromHex(hex));
  }

  private static double contrast(String foreground, String background) {
    return ColorContrast.contrastRatio(
        ColorConversions.fromHex(foreground), ColorConversions.fromHex(background));
  }
}
