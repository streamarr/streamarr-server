/*
 * Copyright 2022 Google LLC
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
 * Modified by Streamarr contributors: the inverse contrast equations are adapted from Material
 * Color Utilities Contrast.lighter/darker (commit 5b3618b16fdc3825e21d5679bafd144662088ea1).
 * Luminance is normalized to 0-1. HSL lightness search replaces HCT tone conversion and its gamut
 * tolerance, retaining a passing rounded RGB result while preserving hue and saturation.
 * Direction selection and OptionalInt results are Streamarr additions. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

import java.util.OptionalInt;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class ContrastAdjustment {

  private static final double LUMINANCE_OFFSET = 0.05;
  private static final int LIGHTNESS_SEARCH_ITERATIONS = 16;

  private final int color;
  private final int background;
  private final float minimumContrast;

  /** Preserves a passing color, or adjusts its lightness; empty if neither polarity can pass. */
  static OptionalInt adjust(int color, int background, float minimumContrast) {
    return new ContrastAdjustment(color, background, minimumContrast).adjust();
  }

  private OptionalInt adjust() {
    if (ColorContrast.contrastRatio(color, background) >= minimumContrast) {
      return OptionalInt.of(color);
    }

    var lighter = ColorContrast.contrastingTextColor(background) == ColorContrast.WHITE;
    var target = targetLuminance(lighter);
    if (target < 0 || target > 1) {
      target = targetLuminance(!lighter);
    }

    if (target < 0 || target > 1) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(atLuminance(target));
  }

  private double targetLuminance(boolean lighter) {
    var luminance = ColorContrast.relativeLuminance(background);
    return lighter
        ? minimumContrast * (luminance + LUMINANCE_OFFSET) - LUMINANCE_OFFSET
        : (luminance + LUMINANCE_OFFSET) / minimumContrast - LUMINANCE_OFFSET;
  }

  private int atLuminance(double target) {
    var lighter = target > ColorContrast.relativeLuminance(color);
    var hsl = ColorConversions.rgbToHsl(color);
    var failing = hsl[2];
    var passing = lighter ? 1f : 0f;
    var result = lighter ? ColorContrast.WHITE : ColorContrast.BLACK;

    // Keep a passing RGB endpoint so channel rounding cannot undercut the contrast floor.
    for (var iteration = 0; iteration < LIGHTNESS_SEARCH_ITERATIONS; iteration++) {
      hsl[2] = (failing + passing) / 2f;
      var candidate = ColorConversions.hslToRgb(hsl);
      if (ColorContrast.contrastRatio(candidate, background) < minimumContrast) {
        failing = hsl[2];
        continue;
      }

      passing = hsl[2];
      result = candidate;
    }

    return result;
  }
}
