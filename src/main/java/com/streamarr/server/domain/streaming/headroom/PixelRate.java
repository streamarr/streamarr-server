package com.streamarr.server.domain.streaming.headroom;

/** Width × height × frames per second: the unit of encoding load (ADR 0044). */
public record PixelRate(double pixelsPerSecond) {

  private static final double PIXELS_PER_MEGAPIXEL = 1_000_000;

  public PixelRate {
    if (!Double.isFinite(pixelsPerSecond) || pixelsPerSecond <= 0) {
      throw new IllegalArgumentException("A pixel rate must be positive: " + pixelsPerSecond);
    }
  }

  public static PixelRate of(int width, int height, double framesPerSecond) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException(
          "A picture needs a positive width and height: " + width + "x" + height);
    }

    return new PixelRate((double) width * height * framesPerSecond);
  }

  public double megapixelsPerSecond() {
    return pixelsPerSecond / PIXELS_PER_MEGAPIXEL;
  }
}
