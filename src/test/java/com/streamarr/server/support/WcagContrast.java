package com.streamarr.server.support;

import java.awt.Color;

/** Independent test oracle from https://www.w3.org/TR/WCAG22/#dfn-relative-luminance. */
public final class WcagContrast {

  private WcagContrast() {}

  public static double luminance(String hex) {
    return luminance(Color.decode(hex).getRGB());
  }

  public static double luminance(int rgb) {
    var color = new Color(rgb);
    return 0.2126 * linear(color.getRed())
        + 0.7152 * linear(color.getGreen())
        + 0.0722 * linear(color.getBlue());
  }

  public static double ratio(String foreground, String background) {
    return ratio(Color.decode(foreground).getRGB(), Color.decode(background).getRGB());
  }

  public static double ratio(int foreground, int background) {
    var first = luminance(foreground);
    var second = luminance(background);
    return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
  }

  private static double linear(int channel) {
    var encoded = channel / 255.0;
    return encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
  }
}
