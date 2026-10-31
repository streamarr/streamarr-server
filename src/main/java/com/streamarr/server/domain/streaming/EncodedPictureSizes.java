package com.streamarr.server.domain.streaming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.NonNull;

/** The picture sizes that the worker encodes a variant at. */
public final class EncodedPictureSizes {

  /** Bounds that leave a picture as large as its source allows. */
  public static final PictureSize NO_BOUNDS = new PictureSize(Integer.MAX_VALUE, Integer.MAX_VALUE);

  private static final int SMALLEST_DIMENSION = 2;

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
   * The pictures with the source's aspect ratio that the worker encodes within the bounds, largest
   * first, never taller than the source and never wider than its width rounded up to even. Each is
   * at least two pixels in each direction, the smallest picture that holds a 4:2:0 chroma sample.
   */
  public static Stream<PictureSize> picturesWithin(
      @NonNull PictureSize source, @NonNull PictureSize bounds) {
    if (source.width() <= 0 || source.height() <= 0) {
      return Stream.empty();
    }

    var widthBound = Math.min(bounds.width(), source.width() + source.width() % 2);
    // FFmpeg rounds the width to the nearest multiple of two, so a height whose exact width is
    // up to one pixel past the bound can still fit. No taller height fits.
    var tallestHeightThatCanFit = ((long) widthBound + 1) * source.height() / source.width();
    var largestHeight =
        (int) Math.min(Math.min(bounds.height(), source.height()), tallestHeightThatCanFit);
    return IntStream.iterate(
            largestHeight - largestHeight % 2,
            height -> height >= SMALLEST_DIMENSION,
            height -> height - 2)
        .mapToObj(height -> atHeight(source, height))
        .filter(picture -> picture.width() <= widthBound)
        .takeWhile(picture -> picture.width() >= SMALLEST_DIMENSION);
  }

  /**
   * Whether a picture of the source reaches the box: the picture fits the box in either orientation
   * and the next larger picture of the source that the worker encodes would not. Even sides can
   * leave a picture that reaches its box a few pixels short of the box's side.
   */
  public static boolean reaches(
      @NonNull PictureSize source, @NonNull PictureSize picture, @NonNull PictureSize box) {
    var nextLarger = atHeight(source, picture.height() + 2);
    return picture.fitsWithinEitherOrientation(box) && !nextLarger.fitsWithinEitherOrientation(box);
  }

  /** Whether the worker encodes the source at any size. */
  public static boolean hasEncodablePicture(@NonNull PictureSize source) {
    return picturesWithin(source, NO_BOUNDS).findAny().isPresent();
  }
}
