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
 * Modified by Streamarr contributors: adapted from AndroidX ColorCutQuantizer (androidx commit
 * 9748764301e5dce66cbf297f6778fa658768c213). android.graphics.Color and ColorUtils calls are
 * replaced with ColorConversions, the filter array with a single SwatchFilter, the dimension
 * constants with an enum, and the vestigial write-back of quantized values into the input pixel
 * array is removed. Defensive branches that are unreachable through this class's construction
 * paths (unsplittable boxes cannot outrank splittable ones by volume) are removed, and the
 * split-point search returns its clamped fallback directly instead of an unreachable sentinel.
 * See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A color quantizer based on the median-cut algorithm, optimized for picking out distinct colors
 * rather than representative colors: boxes are split by color volume instead of population.
 */
final class ColorCutQuantizer {

  private static final int QUANTIZE_WORD_WIDTH = 5;
  private static final int QUANTIZE_WORD_MASK = (1 << QUANTIZE_WORD_WIDTH) - 1;

  private static final Comparator<Vbox> VBOX_COMPARATOR_VOLUME =
      Comparator.comparingInt(Vbox::getVolume).reversed();

  private final int[] colors;
  private final int[] histogram;
  private final SwatchFilter filter;
  private final List<Swatch> quantizedColors;

  private enum ColorComponent {
    RED,
    GREEN,
    BLUE
  }

  ColorCutQuantizer(int[] pixels, int maxColors, SwatchFilter filter) {
    this.filter = filter;

    histogram = new int[1 << (QUANTIZE_WORD_WIDTH * 3)];
    for (var pixel : pixels) {
      histogram[quantizeFromRgb888(pixel)]++;
    }

    var distinctColorCount = 0;
    for (var color = 0; color < histogram.length; color++) {
      if (histogram[color] > 0 && shouldIgnoreColor(color)) {
        histogram[color] = 0;
      }
      if (histogram[color] > 0) {
        distinctColorCount++;
      }
    }

    colors = new int[distinctColorCount];
    var distinctColorIndex = 0;
    for (var color = 0; color < histogram.length; color++) {
      if (histogram[color] > 0) {
        colors[distinctColorIndex++] = color;
      }
    }

    if (distinctColorCount <= maxColors) {
      quantizedColors = new ArrayList<>();
      for (var color : colors) {
        quantizedColors.add(new Swatch(approximateToRgb888(color), histogram[color]));
      }
      return;
    }
    quantizedColors = quantizePixels(maxColors);
  }

  List<Swatch> getQuantizedColors() {
    return quantizedColors;
  }

  private List<Swatch> quantizePixels(int maxColors) {
    var queue = new PriorityQueue<>(maxColors, VBOX_COMPARATOR_VOLUME);
    queue.offer(new Vbox(0, colors.length - 1));
    splitBoxes(queue, maxColors);
    return generateAverageColors(queue);
  }

  private void splitBoxes(PriorityQueue<Vbox> queue, int maxSize) {
    while (queue.size() < maxSize) {
      var vbox = queue.poll();
      queue.offer(vbox.splitBox());
      queue.offer(vbox);
    }
  }

  private List<Swatch> generateAverageColors(Collection<Vbox> vboxes) {
    var averaged = new ArrayList<Swatch>(vboxes.size());
    for (var vbox : vboxes) {
      var swatch = vbox.getAverageColor();
      if (!shouldIgnoreColor(swatch)) {
        averaged.add(swatch);
      }
    }
    return averaged;
  }

  /** A tightly fitting box around a subrange of {@link #colors} in quantized color space. */
  private final class Vbox {

    private final int lowerIndex;
    private int upperIndex;
    private int population;
    private int minRed;
    private int maxRed;
    private int minGreen;
    private int maxGreen;
    private int minBlue;
    private int maxBlue;

    Vbox(int lowerIndex, int upperIndex) {
      this.lowerIndex = lowerIndex;
      this.upperIndex = upperIndex;
      fitBox();
    }

    int getVolume() {
      return (maxRed - minRed + 1) * (maxGreen - minGreen + 1) * (maxBlue - minBlue + 1);
    }

    private void fitBox() {
      minRed = minGreen = minBlue = Integer.MAX_VALUE;
      maxRed = maxGreen = maxBlue = Integer.MIN_VALUE;
      var count = 0;

      for (var i = lowerIndex; i <= upperIndex; i++) {
        var color = colors[i];
        count += histogram[color];
        minRed = Math.min(minRed, quantizedRed(color));
        maxRed = Math.max(maxRed, quantizedRed(color));
        minGreen = Math.min(minGreen, quantizedGreen(color));
        maxGreen = Math.max(maxGreen, quantizedGreen(color));
        minBlue = Math.min(minBlue, quantizedBlue(color));
        maxBlue = Math.max(maxBlue, quantizedBlue(color));
      }
      population = count;
    }

    Vbox splitBox() {
      var splitPoint = findSplitPoint();
      var newBox = new Vbox(splitPoint + 1, upperIndex);
      upperIndex = splitPoint;
      fitBox();
      return newBox;
    }

    private ColorComponent getLongestColorDimension() {
      var redLength = maxRed - minRed;
      var greenLength = maxGreen - minGreen;
      var blueLength = maxBlue - minBlue;
      if (redLength >= greenLength && redLength >= blueLength) {
        return ColorComponent.RED;
      }
      if (greenLength >= redLength && greenLength >= blueLength) {
        return ColorComponent.GREEN;
      }
      return ColorComponent.BLUE;
    }

    /**
     * Sorts the box's colors along its longest dimension and returns the index of the color that
     * carries the box past half of its population, clamped so the split always shrinks the box.
     */
    private int findSplitPoint() {
      var longestDimension = getLongestColorDimension();

      modifySignificantOctet(longestDimension);
      Arrays.sort(colors, lowerIndex, upperIndex + 1);
      modifySignificantOctet(longestDimension);

      var midPoint = population / 2;
      var count = 0;
      for (var i = lowerIndex; i < upperIndex; i++) {
        count += histogram[colors[i]];
        if (count >= midPoint) {
          return i;
        }
      }
      return upperIndex - 1;
    }

    private void modifySignificantOctet(ColorComponent dimension) {
      for (var i = lowerIndex; i <= upperIndex; i++) {
        colors[i] = reorderColor(colors[i], dimension);
      }
    }

    Swatch getAverageColor() {
      var redSum = 0;
      var greenSum = 0;
      var blueSum = 0;
      var totalPopulation = 0;

      for (var i = lowerIndex; i <= upperIndex; i++) {
        var color = colors[i];
        var colorPopulation = histogram[color];
        totalPopulation += colorPopulation;
        redSum += colorPopulation * quantizedRed(color);
        greenSum += colorPopulation * quantizedGreen(color);
        blueSum += colorPopulation * quantizedBlue(color);
      }

      var redMean = Math.round(redSum / (float) totalPopulation);
      var greenMean = Math.round(greenSum / (float) totalPopulation);
      var blueMean = Math.round(blueSum / (float) totalPopulation);
      return new Swatch(approximateToRgb888(redMean, greenMean, blueMean), totalPopulation);
    }
  }

  /**
   * Repacks a quantized color so the given dimension occupies the most significant bits, allowing
   * {@link Arrays#sort(int[], int, int)} to order colors along that dimension. Applying the same
   * reordering twice restores the original packing.
   */
  private static int reorderColor(int color, ColorComponent dimension) {
    return switch (dimension) {
      case RED -> color;
      case GREEN ->
          quantizedGreen(color) << (2 * QUANTIZE_WORD_WIDTH)
              | quantizedRed(color) << QUANTIZE_WORD_WIDTH
              | quantizedBlue(color);
      case BLUE ->
          quantizedBlue(color) << (2 * QUANTIZE_WORD_WIDTH)
              | quantizedGreen(color) << QUANTIZE_WORD_WIDTH
              | quantizedRed(color);
    };
  }

  private boolean shouldIgnoreColor(int quantizedColor) {
    var rgb = approximateToRgb888(quantizedColor);
    return !filter.isAllowed(rgb, ColorConversions.rgbToHsl(rgb));
  }

  private boolean shouldIgnoreColor(Swatch swatch) {
    return !filter.isAllowed(swatch.rgb(), swatch.hsl());
  }

  private static int quantizeFromRgb888(int color) {
    var r = modifyWordWidth(ColorConversions.red(color), 8, QUANTIZE_WORD_WIDTH);
    var g = modifyWordWidth(ColorConversions.green(color), 8, QUANTIZE_WORD_WIDTH);
    var b = modifyWordWidth(ColorConversions.blue(color), 8, QUANTIZE_WORD_WIDTH);
    return r << (2 * QUANTIZE_WORD_WIDTH) | g << QUANTIZE_WORD_WIDTH | b;
  }

  private static int approximateToRgb888(int quantizedColor) {
    return approximateToRgb888(
        quantizedRed(quantizedColor),
        quantizedGreen(quantizedColor),
        quantizedBlue(quantizedColor));
  }

  private static int approximateToRgb888(int r, int g, int b) {
    return ColorConversions.rgb(
        modifyWordWidth(r, QUANTIZE_WORD_WIDTH, 8),
        modifyWordWidth(g, QUANTIZE_WORD_WIDTH, 8),
        modifyWordWidth(b, QUANTIZE_WORD_WIDTH, 8));
  }

  private static int quantizedRed(int color) {
    return (color >> (2 * QUANTIZE_WORD_WIDTH)) & QUANTIZE_WORD_MASK;
  }

  private static int quantizedGreen(int color) {
    return (color >> QUANTIZE_WORD_WIDTH) & QUANTIZE_WORD_MASK;
  }

  private static int quantizedBlue(int color) {
    return color & QUANTIZE_WORD_MASK;
  }

  private static int modifyWordWidth(int value, int currentWidth, int targetWidth) {
    if (targetWidth > currentWidth) {
      return (value << (targetWidth - currentWidth)) & ((1 << targetWidth) - 1);
    }
    return (value >> (currentWidth - targetWidth)) & ((1 << targetWidth) - 1);
  }
}
