/*
 * Copyright 2018 The Android Open Source Project
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
 * Modified by Streamarr contributors: the default swatch count and sample-area constants are
 * adapted from AndroidX Palette (androidx commit 9748764301e5dce66cbf297f6778fa658768c213);
 * target scoring is delegated to Palette. The corner averaging, opaque-coverage gating, and
 * pixel sampling are Streamarr additions. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

import com.streamarr.server.domain.media.AmbientColors;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Derives ambient UI colors from artwork: a linear-light average per image quadrant for corner
 * gradient tinting, a saturation-weighted dominant color for accents, and the dark and light target
 * swatches theme surfaces are built from.
 */
public final class AmbientColorExtractor {

  private static final int MIN_OPAQUE_ALPHA = 125;
  private static final double MIN_OPAQUE_RATIO = 0.1;
  private static final int MAX_SAMPLED_PIXELS = 112 * 112;
  private static final int MAX_COLOR_COUNT = 16;
  private static final long DETERMINISTIC_SAMPLING_SEED = 0;

  private static final int TOP_LEFT = 0;
  private static final int TOP_RIGHT = 1;
  private static final int BOTTOM_LEFT = 2;
  private static final int BOTTOM_RIGHT = 3;

  private AmbientColorExtractor() {}

  public static Optional<AmbientColors> extract(BufferedImage image) {
    var width = image.getWidth();
    var height = image.getHeight();
    var pixels = image.getRGB(0, 0, width, height, null, 0, width);

    var opaquePixels = collectOpaquePixels(pixels);
    if (opaquePixels.length < pixels.length * MIN_OPAQUE_RATIO) {
      return Optional.empty();
    }

    var quadrants = accumulateQuadrants(pixels, width, height);
    var wholeImage = new LinearAccumulator();
    for (var quadrant : quadrants) {
      wholeImage.addAll(quadrant);
    }

    var palette = quantize(opaquePixels);
    return Optional.of(
        AmbientColors.builder()
            .topLeft(quadrantHex(quadrants[TOP_LEFT], wholeImage))
            .topRight(quadrantHex(quadrants[TOP_RIGHT], wholeImage))
            .bottomRight(quadrantHex(quadrants[BOTTOM_RIGHT], wholeImage))
            .bottomLeft(quadrantHex(quadrants[BOTTOM_LEFT], wholeImage))
            .primary(ColorConversions.toHex(selectPrimaryColor(palette)))
            .darkVibrant(targetHex(palette, Target.DARK_VIBRANT))
            .darkMuted(targetHex(palette, Target.DARK_MUTED))
            .lightVibrant(targetHex(palette, Target.LIGHT_VIBRANT))
            .lightMuted(targetHex(palette, Target.LIGHT_MUTED))
            .build());
  }

  private static String targetHex(Palette palette, Target target) {
    return palette
        .swatchFor(target)
        .map(swatch -> ColorConversions.toHex(swatch.rgb()))
        .orElse(null);
  }

  private static int[] collectOpaquePixels(int[] pixels) {
    var opaque = new int[pixels.length];
    var count = 0;
    for (var pixel : pixels) {
      if (ColorConversions.alpha(pixel) >= MIN_OPAQUE_ALPHA) {
        opaque[count++] = pixel;
      }
    }
    return Arrays.copyOf(opaque, count);
  }

  private static LinearAccumulator[] accumulateQuadrants(int[] pixels, int width, int height) {
    var quadrants =
        new LinearAccumulator[] {
          new LinearAccumulator(),
          new LinearAccumulator(),
          new LinearAccumulator(),
          new LinearAccumulator()
        };

    for (var y = 0; y < height; y++) {
      var rowOffset = y * width;
      var verticalOffset = y * 2 / height * 2;
      for (var x = 0; x < width; x++) {
        var pixel = pixels[rowOffset + x];
        if (ColorConversions.alpha(pixel) >= MIN_OPAQUE_ALPHA) {
          quadrants[verticalOffset + x * 2 / width].add(pixel);
        }
      }
    }
    return quadrants;
  }

  private static String quadrantHex(LinearAccumulator quadrant, LinearAccumulator wholeImage) {
    if (quadrant.isEmpty()) {
      return wholeImage.averageHex();
    }
    return quadrant.averageHex();
  }

  private static Palette quantize(int[] opaquePixels) {
    var sample = samplePixels(opaquePixels);
    var swatches =
        new ColorCutQuantizer(sample, MAX_COLOR_COUNT, SwatchFilter.DEFAULT).getQuantizedColors();
    if (swatches.isEmpty()) {
      swatches =
          new ColorCutQuantizer(sample, MAX_COLOR_COUNT, SwatchFilter.ALLOW_ALL)
              .getQuantizedColors();
    }
    return new Palette(swatches);
  }

  private static int selectPrimaryColor(Palette palette) {
    return palette.swatchFor(Target.VIBRANT).orElse(palette.dominantSwatch()).rgb();
  }

  private static int[] samplePixels(int[] opaquePixels) {
    if (opaquePixels.length <= MAX_SAMPLED_PIXELS) {
      return opaquePixels;
    }
    var sample = Arrays.copyOf(opaquePixels, MAX_SAMPLED_PIXELS);
    var random = new SplittableRandom(DETERMINISTIC_SAMPLING_SEED);
    for (var i = MAX_SAMPLED_PIXELS; i < opaquePixels.length; i++) {
      var replacementIndex = random.nextInt(i + 1);
      if (replacementIndex < sample.length) {
        sample[replacementIndex] = opaquePixels[i];
      }
    }
    return sample;
  }

  private static final class LinearAccumulator {

    private double red;
    private double green;
    private double blue;
    private int count;

    void add(int rgb) {
      red += ColorConversions.srgbToLinear(ColorConversions.red(rgb));
      green += ColorConversions.srgbToLinear(ColorConversions.green(rgb));
      blue += ColorConversions.srgbToLinear(ColorConversions.blue(rgb));
      count++;
    }

    void addAll(LinearAccumulator other) {
      red += other.red;
      green += other.green;
      blue += other.blue;
      count += other.count;
    }

    boolean isEmpty() {
      return count == 0;
    }

    String averageHex() {
      return ColorConversions.toHex(
          ColorConversions.rgb(
              ColorConversions.linearToSrgb(red / count),
              ColorConversions.linearToSrgb(green / count),
              ColorConversions.linearToSrgb(blue / count)));
    }
  }
}
