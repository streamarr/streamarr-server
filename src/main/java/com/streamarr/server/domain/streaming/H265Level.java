package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

// ITU-T H.265 Tables A.8 and A.9, in the Main tier. Level 8.5 has no limits, so it is left out.
enum H265Level {
  LEVEL_1_0(10, 36_864, 350, 552_960L, 128),
  LEVEL_2_0(20, 122_880, 1_500, 3_686_400L, 1_500),
  LEVEL_2_1(21, 245_760, 3_000, 7_372_800L, 3_000),
  LEVEL_3_0(30, 552_960, 6_000, 16_588_800L, 6_000),
  LEVEL_3_1(31, 983_040, 10_000, 33_177_600L, 10_000),
  LEVEL_4_0(40, 2_228_224, 12_000, 66_846_720L, 12_000),
  LEVEL_4_1(41, 2_228_224, 20_000, 133_693_440L, 20_000),
  LEVEL_5_0(50, 8_912_896, 25_000, 267_386_880L, 25_000),
  LEVEL_5_1(51, 8_912_896, 40_000, 534_773_760L, 40_000),
  LEVEL_5_2(52, 8_912_896, 60_000, 1_069_547_520L, 60_000),
  LEVEL_6_0(60, 35_651_584, 60_000, 1_069_547_520L, 60_000),
  LEVEL_6_1(61, 35_651_584, 120_000, 2_139_095_040L, 120_000),
  LEVEL_6_2(62, 35_651_584, 240_000, 4_278_190_080L, 240_000);

  // Table A.10: the Main profile's CpbVclFactor, in bits per unit of MaxBR and MaxCPB.
  private static final long MAIN_PROFILE_BITS_PER_UNIT = 1_000;

  // The worker caps an encode at the variant's bitrate with a buffer of twice that bitrate.
  private static final int BUFFER_SIZE_PER_BITRATE = 2;

  // The smallest coding block is at least 8 luma samples on each side, and libx265's is 8, so a
  // coded picture covers whole 8-sample blocks.
  private static final int CODED_BLOCK_SIZE = 8;

  private static final int GENERAL_LEVEL_IDC_PER_LEVEL = 3;

  private final int level;
  private final long maxLumaPictureSize;
  private final long maxCodedPictureBufferUnits;
  private final long maxLumaSampleRate;
  private final long maxBitrateUnits;

  H265Level(
      int level,
      long maxLumaPictureSize,
      long maxCodedPictureBufferUnits,
      long maxLumaSampleRate,
      long maxBitrateUnits) {
    this.level = level;
    this.maxLumaPictureSize = maxLumaPictureSize;
    this.maxCodedPictureBufferUnits = maxCodedPictureBufferUnits;
    this.maxLumaSampleRate = maxLumaSampleRate;
    this.maxBitrateUnits = maxBitrateUnits;
  }

  static Optional<H265Level> smallestAllowing(EncodedVideoOutput output) {
    return Arrays.stream(values()).filter(level -> level.allows(output)).findFirst();
  }

  /** The highest Main tier bitrate that a level at or below {@code maxLevel} allows an encode. */
  static OptionalLong highestBitrateAtOrBelow(int maxLevel) {
    return Arrays.stream(values())
        .filter(level -> level.level <= maxLevel)
        .mapToLong(H265Level::highestBitrate)
        .max();
  }

  /** The level as major x 10 + minor, the form that libx265 takes. */
  int level() {
    return level;
  }

  // Main profile, compatible with Main and Main 10, in the Main tier, with general_level_idc at 30
  // x the level and the progressive-source and frame-only constraint flags.
  String mainProfileCodecString() {
    return String.format(Locale.ROOT, "hvc1.1.6.L%d.90", level * GENERAL_LEVEL_IDC_PER_LEVEL);
  }

  // Main 10 in the Main tier, compatible with Main 10 alone, with the same level and constraint
  // flags as Main. Main 10 shares Main's limits and CpbVclFactor.
  String main10ProfileCodecString() {
    return String.format(Locale.ROOT, "hvc1.2.4.L%d.90", level * GENERAL_LEVEL_IDC_PER_LEVEL);
  }

  private long highestBitrate() {
    return Math.min(
        maxBitrateUnits * MAIN_PROFILE_BITS_PER_UNIT,
        maxCodedPictureBufferUnits * MAIN_PROFILE_BITS_PER_UNIT / BUFFER_SIZE_PER_BITRATE);
  }

  private boolean allows(EncodedVideoOutput output) {
    var width = codedSide(output.width());
    var height = codedSide(output.height());
    var pictureSize = width * height;
    // The picture bound and positive int fraction components keep the sample rate within long.
    return holdsPicture(width, height, pictureSize)
        && pictureSize * output.frameRate().numerator()
            <= maxLumaSampleRate * output.frameRate().denominator()
        && holdsBitrate(output);
  }

  private static long codedSide(int side) {
    return (long) Math.ceilDiv(side, CODED_BLOCK_SIZE) * CODED_BLOCK_SIZE;
  }

  // Each side is at most the square root of 8 x MaxLumaPs.
  private boolean holdsPicture(long width, long height, long pictureSize) {
    var maxSideSquared = 8 * maxLumaPictureSize;
    return pictureSize <= maxLumaPictureSize
        && width * width <= maxSideSquared
        && height * height <= maxSideSquared;
  }

  private boolean holdsBitrate(EncodedVideoOutput output) {
    return output.bitrate() <= maxBitrateUnits * MAIN_PROFILE_BITS_PER_UNIT
        && output.bitrate() * BUFFER_SIZE_PER_BITRATE
            <= maxCodedPictureBufferUnits * MAIN_PROFILE_BITS_PER_UNIT;
  }
}
