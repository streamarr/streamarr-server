package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.IntStream;
import lombok.NonNull;

/** The picture sizes that the worker encodes a variant at. */
public final class EncodedPictureSizes {

  private EncodedPictureSizes() {}

  // The worker produces 4:2:0 and scales with scale=-2:<height>. Keep the height even and
  // match FFmpeg's width rounding to the nearest multiple of two, with ties away from zero.
  public static PictureSize atHeight(@NonNull PictureSize source, int targetHeight) {
    var height = targetHeight - targetHeight % 2;
    var halfWidth =
        BigDecimal.valueOf((long) height * source.width())
            .divide(BigDecimal.valueOf(2L * source.height()), 0, RoundingMode.HALF_UP);
    return new PictureSize(halfWidth.intValueExact() * 2, height);
  }

  /**
   * The height of the largest picture with the source's aspect ratio that the worker encodes within
   * the bounds, never taller than the source.
   */
  public static int largestHeightWithin(@NonNull PictureSize source, @NonNull PictureSize bounds) {
    // FFmpeg rounds the width to the nearest multiple of two, so a height whose exact width is
    // up to one pixel past the bound can still fit. No taller height fits.
    var tallestHeightThatCanFit = ((long) bounds.width() + 1) * source.height() / source.width();
    var largestHeight =
        (int) Math.min(Math.min(bounds.height(), source.height()), tallestHeightThatCanFit);
    return IntStream.iterate(
            largestHeight - largestHeight % 2, height -> height >= 0, height -> height - 2)
        .filter(height -> atHeight(source, height).width() <= bounds.width())
        .findFirst()
        .orElseThrow();
  }
}
