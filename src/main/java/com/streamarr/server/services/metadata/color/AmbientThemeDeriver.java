package com.streamarr.server.services.metadata.color;

import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.AmbientTheme;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Maps stored ambient colors onto the role slots detail pages theme themselves with. The artwork
 * field (mean corner luminance) decides between a dark and a bright theme; each slot is then a
 * swatch from the artwork or a contrast-checked derivation of one.
 */
public final class AmbientThemeDeriver {

  static final double LIGHT_FAMILY_THRESHOLD = 0.4;

  private static final float DARK_TARGET_LIGHTNESS = 0.26f;
  private static final float LIGHT_TARGET_LIGHTNESS = 0.74f;
  private static final float MIN_ACCENT_CONTRAST = 3.0f;
  private static final int TEXT_PRIMARY_ALPHA = 235;
  private static final int TEXT_SECONDARY_ALPHA = 128;
  private static final int PANEL_WASH_ALPHA = 18;
  private static final int SELECTED_MIX_ALPHA = 77;

  private final AmbientColors colors;
  private final boolean bright;
  private final int base;
  private final int text;
  private final int accent;

  private AmbientThemeDeriver(AmbientColors colors) {
    this.colors = colors;
    bright = meanCornerLuminance() >= LIGHT_FAMILY_THRESHOLD;
    base = selectBase();
    text = ColorContrast.contrastingTextColor(base);
    accent = liftAccent(selectAccent());
  }

  public static AmbientTheme derive(AmbientColors colors) {
    return new AmbientThemeDeriver(colors).theme();
  }

  private AmbientTheme theme() {
    var panel = readableSurface(ColorContrast.composite(text, PANEL_WASH_ALPHA, base));
    var selected = readableSurface(selectSelected());
    var surfaces = new int[] {base, panel, selected};
    return AmbientTheme.builder()
        .base(hex(base))
        .panel(hex(panel))
        .selected(hex(selected))
        .accent(hex(accent))
        .onAccent(
            hex(
                textOver(
                    new int[] {accent}, TEXT_PRIMARY_ALPHA, ColorContrast.MIN_CONTRAST_BODY_TEXT)))
        .textPrimary(
            hex(textOver(surfaces, TEXT_PRIMARY_ALPHA, ColorContrast.MIN_CONTRAST_BODY_TEXT)))
        .textSecondary(
            hex(textOver(surfaces, TEXT_SECONDARY_ALPHA, ColorContrast.MIN_CONTRAST_TITLE_TEXT)))
        .build();
  }

  private double meanCornerLuminance() {
    return Stream.of(colors.topLeft(), colors.topRight(), colors.bottomRight(), colors.bottomLeft())
        .mapToDouble(hex -> ColorContrast.relativeLuminance(ColorConversions.fromHex(hex)))
        .average()
        .orElseThrow();
  }

  private int selectBase() {
    if (bright) {
      return firstSwatch(colors.lightMuted(), colors.lightVibrant())
          .orElseGet(() -> withLightness(primary(), LIGHT_TARGET_LIGHTNESS));
    }

    return firstSwatch(colors.darkMuted(), colors.darkVibrant())
        .orElseGet(() -> withLightness(primary(), DARK_TARGET_LIGHTNESS));
  }

  private int selectAccent() {
    if (!bright) {
      return primary();
    }

    return firstSwatch(colors.darkVibrant(), colors.darkMuted())
        .orElseGet(() -> withLightness(primary(), DARK_TARGET_LIGHTNESS));
  }

  /** Moves the accent's lightness toward the text polarity until it stands out from base. */
  private int liftAccent(int candidate) {
    return ContrastAdjustment.adjust(candidate, base, MIN_ACCENT_CONTRAST).orElseThrow();
  }

  private int selectSelected() {
    var vibrant = bright ? colors.lightVibrant() : colors.darkVibrant();
    return firstSwatch(vibrant)
        .filter(swatch -> swatch != base)
        .orElseGet(() -> ColorContrast.composite(accent, SELECTED_MIX_ALPHA, base));
  }

  private int readableSurface(int candidate) {
    return ContrastAdjustment.adjust(candidate, text, ColorContrast.MIN_CONTRAST_BODY_TEXT)
        .orElseThrow();
  }

  private static int textOver(int[] backgrounds, int preferredAlpha, float minContrast) {
    var polarity = ColorContrast.contrastingTextColor(backgrounds[0]);
    var background =
        Arrays.stream(backgrounds)
            .boxed()
            .min(
                Comparator.comparingDouble(
                    surface -> ColorContrast.contrastRatio(polarity, surface)))
            .orElseThrow();
    var floor = ColorContrast.minimumAlpha(polarity, background, minContrast).orElseThrow();
    return ColorContrast.composite(polarity, Math.max(preferredAlpha, floor), background);
  }

  private int primary() {
    return ColorConversions.fromHex(colors.primary());
  }

  private static Optional<Integer> firstSwatch(String... hexes) {
    return Stream.of(hexes).filter(Objects::nonNull).findFirst().map(ColorConversions::fromHex);
  }

  private static int withLightness(int rgb, float lightness) {
    var hsl = ColorConversions.rgbToHsl(rgb);
    hsl[2] = lightness;
    return ColorConversions.hslToRgb(hsl);
  }

  private static String hex(int rgb) {
    return ColorConversions.toHex(rgb);
  }
}
