package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** The codec profile that an encoded variant forces (ADR 0013). */
public enum EncodeCodecProfile {
  H264_HIGH(VideoCodecProfile.H264_HIGH),
  HEVC_MAIN(VideoCodecProfile.HEVC_MAIN),
  HEVC_MAIN_10(VideoCodecProfile.HEVC_MAIN_10),
  AV1_MAIN(VideoCodecProfile.AV1_MAIN);

  private final VideoCodecProfile codecProfile;

  EncodeCodecProfile(VideoCodecProfile codecProfile) {
    this.codecProfile = codecProfile;
  }

  /** The codec families that the server encodes, in name order. */
  public static List<String> codecFamilyNames() {
    return Arrays.stream(values())
        .map(EncodeCodecProfile::codecFamilyName)
        .distinct()
        .sorted()
        .toList();
  }

  /** The codec profile that the probe reports for the encoded video. */
  public VideoCodecProfile codecProfile() {
    return codecProfile;
  }

  /** The codec family's probed name, which the transcode decision carries. */
  public String codecFamilyName() {
    return codecProfile.codecFamily().name().toLowerCase(Locale.ROOT);
  }

  /** Whether the profile holds samples of the bit depth: H.264 High and H.265 Main hold 8 bits. */
  public boolean holdsBitDepth(int bitDepth) {
    return switch (this) {
      case H264_HIGH, HEVC_MAIN -> bitDepth == 8;
      case HEVC_MAIN_10 -> bitDepth == 10;
      case AV1_MAIN -> bitDepth == 8 || bitDepth == 10;
    };
  }

  /** The profile of the same codec family that holds 8 bits: H.265 Main for H.265 Main 10. */
  public EncodeCodecProfile eightBitProfile() {
    return switch (this) {
      case HEVC_MAIN_10 -> HEVC_MAIN;
      case H264_HIGH, HEVC_MAIN, AV1_MAIN -> this;
    };
  }

  /** The highest bitrate that a codec level at or below {@code maxLevel} allows an encode. */
  OptionalLong highestBitrateAtOrBelow(int maxLevel) {
    return switch (this) {
      case H264_HIGH -> H264Level.highestBitrateAtOrBelow(maxLevel);
      case HEVC_MAIN, HEVC_MAIN_10 -> H265Level.highestBitrateAtOrBelow(maxLevel);
      case AV1_MAIN -> Av1Level.highestBitrateAtOrBelow(maxLevel);
    };
  }
}
