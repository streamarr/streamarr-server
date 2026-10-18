package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

// ITU-T H.264 Table A-1. Level 1.1 allows everything that level 1b allows, so level 1b is left out.
enum H264Level {
  LEVEL_1_0(10, 1_485, 99, 396, 64, 175),
  LEVEL_1_1(11, 3_000, 396, 900, 192, 500),
  LEVEL_1_2(12, 6_000, 396, 2_376, 384, 1_000),
  LEVEL_1_3(13, 11_880, 396, 2_376, 768, 2_000),
  LEVEL_2_0(20, 11_880, 396, 2_376, 2_000, 2_000),
  LEVEL_2_1(21, 19_800, 792, 4_752, 4_000, 4_000),
  LEVEL_2_2(22, 20_250, 1_620, 8_100, 4_000, 4_000),
  LEVEL_3_0(30, 40_500, 1_620, 8_100, 10_000, 10_000),
  LEVEL_3_1(31, 108_000, 3_600, 18_000, 14_000, 14_000),
  LEVEL_3_2(32, 216_000, 5_120, 20_480, 20_000, 20_000),
  LEVEL_4_0(40, 245_760, 8_192, 32_768, 20_000, 25_000),
  LEVEL_4_1(41, 245_760, 8_192, 32_768, 50_000, 62_500),
  LEVEL_4_2(42, 522_240, 8_704, 34_816, 50_000, 62_500),
  LEVEL_5_0(50, 589_824, 22_080, 110_400, 135_000, 135_000),
  LEVEL_5_1(51, 983_040, 36_864, 184_320, 240_000, 240_000),
  LEVEL_5_2(52, 2_073_600, 36_864, 184_320, 240_000, 240_000),
  LEVEL_6_0(60, 4_177_920, 139_264, 696_320, 240_000, 240_000),
  LEVEL_6_1(61, 8_355_840, 139_264, 696_320, 480_000, 480_000),
  LEVEL_6_2(62, 16_711_680, 139_264, 696_320, 800_000, 800_000);

  // Table A-2: the High profile's cpbBrVclFactor, in bits per unit of MaxBR and MaxCPB.
  private static final long HIGH_PROFILE_BITS_PER_UNIT = 1_250;

  // libx264's medium preset keeps 3 reference frames beside the frame it decodes. A level with
  // room for 4 frames is the level that libx264 chose on its own in every measured encode.
  private static final int DECODED_PICTURE_BUFFER_FRAMES = 4;

  // The worker caps an encode at the variant's bitrate with a buffer of twice that bitrate.
  private static final int BUFFER_SIZE_PER_BITRATE = 2;

  private static final String HIGH_PROFILE_CODEC_STRING_PREFIX = "avc1.6400";

  private final int levelIdc;
  private final long maxMacroblocksPerSecond;
  private final long maxFrameSize;
  private final long maxDecodedPictureBufferMacroblocks;
  private final long maxBitrateUnits;
  private final long maxCodedPictureBufferUnits;

  H264Level(
      int levelIdc,
      long maxMacroblocksPerSecond,
      long maxFrameSize,
      long maxDecodedPictureBufferMacroblocks,
      long maxBitrateUnits,
      long maxCodedPictureBufferUnits) {
    this.levelIdc = levelIdc;
    this.maxMacroblocksPerSecond = maxMacroblocksPerSecond;
    this.maxFrameSize = maxFrameSize;
    this.maxDecodedPictureBufferMacroblocks = maxDecodedPictureBufferMacroblocks;
    this.maxBitrateUnits = maxBitrateUnits;
    this.maxCodedPictureBufferUnits = maxCodedPictureBufferUnits;
  }

  static Optional<H264Level> smallestAllowing(EncodedVideoOutput output) {
    return Arrays.stream(values()).filter(level -> level.allows(output)).findFirst();
  }

  /** The level as level_idc, which is major x 10 + minor. */
  int levelIdc() {
    return levelIdc;
  }

  String highProfileCodecString() {
    return HIGH_PROFILE_CODEC_STRING_PREFIX
        + HexFormat.of().withUpperCase().toHexDigits((byte) levelIdc);
  }

  private boolean allows(EncodedVideoOutput output) {
    var frameSize = (long) output.widthInMacroblocks() * output.heightInMacroblocks();
    return holdsFrame(output, frameSize)
        && holdsMacroblockRate(output, frameSize)
        && holdsDecodedPictureBuffer(frameSize)
        && holdsBitrate(output);
  }

  // Each side of the frame, in macroblocks, is at most the square root of 8 x MaxFS.
  private boolean holdsFrame(EncodedVideoOutput output, long frameSize) {
    var maxSideSquared = 8 * maxFrameSize;
    return frameSize <= maxFrameSize
        && (long) output.widthInMacroblocks() * output.widthInMacroblocks() <= maxSideSquared
        && (long) output.heightInMacroblocks() * output.heightInMacroblocks() <= maxSideSquared;
  }

  private boolean holdsMacroblockRate(EncodedVideoOutput output, long frameSize) {
    return frameSize * output.frameRate().numerator()
        <= maxMacroblocksPerSecond * output.frameRate().denominator();
  }

  private boolean holdsDecodedPictureBuffer(long frameSize) {
    return frameSize * DECODED_PICTURE_BUFFER_FRAMES <= maxDecodedPictureBufferMacroblocks;
  }

  private boolean holdsBitrate(EncodedVideoOutput output) {
    return output.bitrate() <= maxBitrateUnits * HIGH_PROFILE_BITS_PER_UNIT
        && output.bitrate() * BUFFER_SIZE_PER_BITRATE
            <= maxCodedPictureBufferUnits * HIGH_PROFILE_BITS_PER_UNIT;
  }
}
