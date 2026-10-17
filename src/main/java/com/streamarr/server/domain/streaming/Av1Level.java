package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

// The AV1 specification's Annex A.3, in the Main tier. Levels without defined limits are left out.
// Levels 5.3 and 6.3 differ from levels 5.2 and 6.2 only in limits that the table does not check.
enum Av1Level {
  LEVEL_2_0(0, 147_456, 2_048, 1_152, 4_423_680L, 1_500_000),
  LEVEL_2_1(1, 278_784, 2_816, 1_584, 8_363_520L, 3_000_000),
  LEVEL_3_0(4, 665_856, 4_352, 2_448, 19_975_680L, 6_000_000),
  LEVEL_3_1(5, 1_065_024, 5_504, 3_096, 31_950_720L, 10_000_000),
  LEVEL_4_0(8, 2_359_296, 6_144, 3_456, 70_778_880L, 12_000_000),
  LEVEL_4_1(9, 2_359_296, 6_144, 3_456, 141_557_760L, 20_000_000),
  LEVEL_5_0(12, 8_912_896, 8_192, 4_352, 267_386_880L, 30_000_000),
  LEVEL_5_1(13, 8_912_896, 8_192, 4_352, 534_773_760L, 40_000_000),
  LEVEL_5_2(14, 8_912_896, 8_192, 4_352, 1_069_547_520L, 60_000_000),
  LEVEL_6_0(16, 35_651_584, 16_384, 8_704, 1_069_547_520L, 60_000_000),
  LEVEL_6_1(17, 35_651_584, 16_384, 8_704, 2_139_095_040L, 100_000_000),
  LEVEL_6_2(18, 35_651_584, 16_384, 8_704, 4_278_190_080L, 160_000_000);

  private static final int MINOR_LEVELS_PER_MAJOR_LEVEL = 4;
  private static final int FIRST_MAJOR_LEVEL = 2;

  private final int seqLevelIdx;
  private final long maxPictureSize;
  private final int maxWidth;
  private final int maxHeight;
  private final long maxDisplayRate;
  private final long maxMainTierBitrate;

  Av1Level(
      int seqLevelIdx,
      long maxPictureSize,
      int maxWidth,
      int maxHeight,
      long maxDisplayRate,
      long maxMainTierBitrate) {
    this.seqLevelIdx = seqLevelIdx;
    this.maxPictureSize = maxPictureSize;
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
    this.maxDisplayRate = maxDisplayRate;
    this.maxMainTierBitrate = maxMainTierBitrate;
  }

  static Optional<Av1Level> smallestAllowing(EncodedVideoOutput output) {
    return Arrays.stream(values()).filter(level -> level.allows(output)).findFirst();
  }

  /** The level as major x 10 + minor, the form that SVT-AV1 takes. */
  int level() {
    var major = FIRST_MAJOR_LEVEL + seqLevelIdx / MINOR_LEVELS_PER_MAJOR_LEVEL;
    return major * 10 + seqLevelIdx % MINOR_LEVELS_PER_MAJOR_LEVEL;
  }

  // Profile 0 (Main) in the Main tier at 8 bits.
  String mainProfileCodecString() {
    return String.format(Locale.ROOT, "av01.0.%02dM.08", seqLevelIdx);
  }

  private boolean allows(EncodedVideoOutput output) {
    // The picture bounds and positive int fraction components keep both products within long.
    return holdsPicture(output)
        && output.pictureSize() * output.frameRate().numerator()
            <= maxDisplayRate * output.frameRate().denominator()
        && output.bitrate() <= maxMainTierBitrate;
  }

  private boolean holdsPicture(EncodedVideoOutput output) {
    return output.pictureSize() <= maxPictureSize
        && output.width() <= maxWidth
        && output.height() <= maxHeight;
  }
}
