package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import lombok.NonNull;

/** The codec profile that an encoded variant forces (ADR 0013). */
public enum EncodeCodecProfile {
  H264_HIGH(VideoCodecProfile.H264_HIGH),
  AV1_MAIN(VideoCodecProfile.AV1_MAIN);

  private final VideoCodecProfile codecProfile;

  EncodeCodecProfile(VideoCodecProfile codecProfile) {
    this.codecProfile = codecProfile;
  }

  /** The codec profile that an encode of the codec family, by its probed name, forces. */
  public static EncodeCodecProfile forCodecFamilyName(@NonNull String codecFamilyName) {
    return Arrays.stream(values())
        .filter(profile -> profile.codecFamilyName().equals(codecFamilyName))
        .findFirst()
        .orElseThrow(
            () ->
                new UnsupportedVideoEncodeException(
                    "The server has no encode codec profile for " + codecFamilyName));
  }

  /** The codec profile that the probe reports for the encoded video. */
  public VideoCodecProfile codecProfile() {
    return codecProfile;
  }

  /** The codec family's probed name, which the transcode decision carries. */
  public String codecFamilyName() {
    return codecProfile.codecFamily().name().toLowerCase(Locale.ROOT);
  }

  /** The highest bitrate that a codec level at or below {@code maxLevel} allows an encode. */
  OptionalLong highestBitrateAtOrBelow(int maxLevel) {
    return switch (this) {
      case H264_HIGH -> H264Level.highestBitrateAtOrBelow(maxLevel);
      case AV1_MAIN -> Av1Level.highestBitrateAtOrBelow(maxLevel);
    };
  }
}
