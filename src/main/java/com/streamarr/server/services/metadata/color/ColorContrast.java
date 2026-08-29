/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Streamarr contributors: the contrast ratio, minimum-alpha search, and compositing
 * are adapted from AndroidX ColorUtils, and the title/body text color generation from AndroidX
 * Palette.Swatch (androidx commit 9748764301e5dce66cbf297f6778fa658768c213). Luminance reuses
 * ColorConversions' linear-light conversion, backgrounds are always opaque, results are composited
 * to opaque colors, and the mismatched-polarity fallback is removed as unreachable: body contrast
 * implies title contrast, and one of white or black always reaches 4.5:1 because their ratios
 * against any background multiply to 21. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

import java.util.OptionalInt;

/** WCAG contrast math over opaque RGB colors. */
final class ColorContrast {

  static final float MIN_CONTRAST_TITLE_TEXT = 3.0f;
  static final float MIN_CONTRAST_BODY_TEXT = 4.5f;

  private static final int WHITE = 0xFFFFFF;
  private static final int BLACK = 0x000000;
  private static final int OPAQUE_ALPHA = 255;
  private static final int MIN_ALPHA_SEARCH_MAX_ITERATIONS = 10;
  private static final int MIN_ALPHA_SEARCH_PRECISION = 1;
  private static final double LUMINANCE_OFFSET = 0.05;

  /** Opaque text colors that clear the title and body contrast floors over one background. */
  record TextColors(int title, int body) {}

  private ColorContrast() {}

  /** Relative luminance from 0 (black) to 1 (white), as defined by WCAG 2.0. */
  static double relativeLuminance(int rgb) {
    return 0.2126 * ColorConversions.srgbToLinear(ColorConversions.red(rgb))
        + 0.7152 * ColorConversions.srgbToLinear(ColorConversions.green(rgb))
        + 0.0722 * ColorConversions.srgbToLinear(ColorConversions.blue(rgb));
  }

  static double contrastRatio(int foreground, int background) {
    var foregroundLuminance = relativeLuminance(foreground) + LUMINANCE_OFFSET;
    var backgroundLuminance = relativeLuminance(background) + LUMINANCE_OFFSET;
    return Math.max(foregroundLuminance, backgroundLuminance)
        / Math.min(foregroundLuminance, backgroundLuminance);
  }

  /** Composites {@code foreground} at {@code alpha} (0-255) over an opaque background. */
  static int composite(int foreground, int alpha, int background) {
    return ColorConversions.rgb(
        compositeChannel(ColorConversions.red(foreground), alpha, ColorConversions.red(background)),
        compositeChannel(
            ColorConversions.green(foreground), alpha, ColorConversions.green(background)),
        compositeChannel(
            ColorConversions.blue(foreground), alpha, ColorConversions.blue(background)));
  }

  /**
   * Finds the lowest alpha at which {@code foreground} still reaches {@code minContrastRatio} over
   * the background, or empty when even the opaque foreground cannot.
   */
  static OptionalInt minimumAlpha(int foreground, int background, float minContrastRatio) {
    if (contrastRatio(foreground, background) < minContrastRatio) {
      return OptionalInt.empty();
    }

    var minAlpha = 0;
    var maxAlpha = OPAQUE_ALPHA;
    var iterations = 0;
    while (iterations <= MIN_ALPHA_SEARCH_MAX_ITERATIONS
        && maxAlpha - minAlpha > MIN_ALPHA_SEARCH_PRECISION) {
      var testAlpha = (minAlpha + maxAlpha) / 2;
      var testRatio = contrastRatio(composite(foreground, testAlpha, background), background);
      iterations++;
      if (testRatio < minContrastRatio) {
        minAlpha = testAlpha;
        continue;
      }
      maxAlpha = testAlpha;
    }
    return OptionalInt.of(maxAlpha);
  }

  static TextColors textColorsOver(int background) {
    var lightBodyAlpha = minimumAlpha(WHITE, background, MIN_CONTRAST_BODY_TEXT);
    if (lightBodyAlpha.isPresent()) {
      return textColors(WHITE, background, lightBodyAlpha.getAsInt());
    }
    var darkBodyAlpha = minimumAlpha(BLACK, background, MIN_CONTRAST_BODY_TEXT).orElseThrow();
    return textColors(BLACK, background, darkBodyAlpha);
  }

  private static TextColors textColors(int text, int background, int bodyAlpha) {
    var titleAlpha = minimumAlpha(text, background, MIN_CONTRAST_TITLE_TEXT).orElseThrow();
    return new TextColors(
        composite(text, titleAlpha, background), composite(text, bodyAlpha, background));
  }

  private static int compositeChannel(int foreground, int alpha, int background) {
    return (foreground * alpha + background * (OPAQUE_ALPHA - alpha)) / OPAQUE_ALPHA;
  }
}
