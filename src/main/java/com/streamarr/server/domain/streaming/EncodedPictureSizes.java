package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
}
