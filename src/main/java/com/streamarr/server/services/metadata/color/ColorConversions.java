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
 * Modified by Streamarr contributors: the RGB-to-HSL and HSL-to-RGB conversions are adapted from
 * AndroidX ColorUtils (androidx commit 9748764301e5dce66cbf297f6778fa658768c213); packed-int
 * component accessors replace android.graphics.Color, and the sRGB linear-light conversions and
 * hex formatting and parsing are Streamarr additions. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

final class ColorConversions {

  private static final int MAX_CHANNEL = 255;
  private static final float HUE_SEGMENT_DEGREES = 60f;

  private ColorConversions() {}

  static int alpha(int argb) {
    return argb >>> 24;
  }

  static int red(int rgb) {
    return (rgb >> 16) & 0xFF;
  }

  static int green(int rgb) {
    return (rgb >> 8) & 0xFF;
  }

  static int blue(int rgb) {
    return rgb & 0xFF;
  }

  static int rgb(int red, int green, int blue) {
    return (red << 16) | (green << 8) | blue;
  }

  static String toHex(int rgb) {
    return String.format("#%06x", rgb & 0xFFFFFF);
  }

  /** Parses a {@code #rrggbb} string as produced by {@link #toHex(int)}. */
  static int fromHex(String hex) {
    return Integer.parseInt(hex.substring(1), 16);
  }

  /** Returns {hue [0, 360), saturation [0, 1], lightness [0, 1]}. */
  static float[] rgbToHsl(int rgb) {
    var rf = red(rgb) / 255f;
    var gf = green(rgb) / 255f;
    var bf = blue(rgb) / 255f;

    var max = Math.max(rf, Math.max(gf, bf));
    var min = Math.min(rf, Math.min(gf, bf));
    var delta = max - min;

    var lightness = (max + min) / 2f;
    if (delta == 0f) {
      return new float[] {0f, 0f, lightness};
    }

    float hue;
    if (max == rf) {
      hue = ((gf - bf) / delta) % 6f;
    } else if (max == gf) {
      hue = ((bf - rf) / delta) + 2f;
    } else {
      hue = ((rf - gf) / delta) + 4f;
    }
    hue = (hue * HUE_SEGMENT_DEGREES) % 360f;
    if (hue < 0f) {
      hue += 360f;
    }

    var saturation = delta / (1f - Math.abs(2f * lightness - 1f));
    return new float[] {hue, Math.min(saturation, 1f), lightness};
  }

  /** Inverse of {@link #rgbToHsl(int)}; channels are clamped into the sRGB range. */
  static int hslToRgb(float[] hsl) {
    var hue = hsl[0];
    var saturation = hsl[1];
    var lightness = hsl[2];

    var chroma = (1f - Math.abs(2f * lightness - 1f)) * saturation;
    var match = lightness - 0.5f * chroma;
    var secondary = chroma * (1f - Math.abs((hue / HUE_SEGMENT_DEGREES % 2f) - 1f));

    var channels =
        switch ((int) (hue / HUE_SEGMENT_DEGREES)) {
          case 0 -> new float[] {chroma, secondary, 0f};
          case 1 -> new float[] {secondary, chroma, 0f};
          case 2 -> new float[] {0f, chroma, secondary};
          case 3 -> new float[] {0f, secondary, chroma};
          case 4 -> new float[] {secondary, 0f, chroma};
          default -> new float[] {chroma, 0f, secondary};
        };
    return rgb(
        channel(channels[0] + match), channel(channels[1] + match), channel(channels[2] + match));
  }

  static double srgbToLinear(int channel) {
    var c = channel / 255d;
    if (c <= 0.04045) {
      return c / 12.92;
    }
    return Math.pow((c + 0.055) / 1.055, 2.4);
  }

  static int linearToSrgb(double linear) {
    if (linear <= 0.0031308) {
      return (int) Math.round(linear * 12.92 * 255d);
    }
    return (int) Math.round((1.055 * Math.pow(linear, 1 / 2.4) - 0.055) * 255d);
  }

  private static int channel(float value) {
    return Math.clamp(Math.round(MAX_CHANNEL * value), 0, MAX_CHANNEL);
  }
}
