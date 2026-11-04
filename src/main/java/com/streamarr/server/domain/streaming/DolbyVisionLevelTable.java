package com.streamarr.server.domain.streaming;

import java.math.BigInteger;
import java.util.List;
import java.util.OptionalInt;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Dolby's Dolby Vision level table, as FFmpeg 8.1.3 copies it in libavcodec/dovi_rpuenc.c. Each
 * level bounds the pixel rate and the width. The table also bounds the bitrate, which no stored
 * fact gives.
 */
@UtilityClass
class DolbyVisionLevelTable {

  private record Level(int number, long maxPixelsPerSecond, int maxWidth) {}

  private static final List<Level> LEVELS =
      List.of(
          new Level(1, 1280L * 720 * 24, 1280),
          new Level(2, 1280L * 720 * 30, 1280),
          new Level(3, 1920L * 1080 * 24, 1920),
          new Level(4, 1920L * 1080 * 30, 2560),
          new Level(5, 1920L * 1080 * 60, 3840),
          new Level(6, 3840L * 2160 * 24, 3840),
          new Level(7, 3840L * 2160 * 30, 3840),
          new Level(8, 3840L * 2160 * 48, 3840),
          new Level(9, 3840L * 2160 * 60, 3840),
          new Level(10, 3840L * 2160 * 120, 3840),
          new Level(11, 7680L * 4320 * 30, 7680),
          new Level(12, 7680L * 4320 * 60, 7680),
          new Level(13, 7680L * 4320 * 120, 7680));

  /**
   * The lowest level that carries the decoded picture at the maximum frame rate, or empty when the
   * picture exceeds every level. The pixel rate is exact, so 3840×2160 at 24000/1001 fps fits level
   * 6 and 1280×720 at 24001/1000 fps exceeds level 1.
   */
  static OptionalInt lowestLevelFor(
      @NonNull PictureSize decodedSize, @NonNull Fraction maximumFrameRate) {
    var pixelsPerFrameTimesRate =
        BigInteger.valueOf(decodedSize.width())
            .multiply(BigInteger.valueOf(decodedSize.height()))
            .multiply(BigInteger.valueOf(maximumFrameRate.numerator()));
    var rateDenominator = BigInteger.valueOf(maximumFrameRate.denominator());
    return LEVELS.stream()
        .filter(level -> decodedSize.width() <= level.maxWidth())
        .filter(
            level ->
                pixelsPerFrameTimesRate.compareTo(
                        BigInteger.valueOf(level.maxPixelsPerSecond()).multiply(rateDenominator))
                    <= 0)
        .mapToInt(Level::number)
        .findFirst();
  }
}
