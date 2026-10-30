package com.streamarr.server.domain.streaming;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.util.Locale;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The codec profile, tier, level and codec string that one encoded variant forces (ADR 0013). The
 * level is the smallest whose limits allow the variant's planned output.
 */
@Builder
public record VideoEncodeDecision(
    @NonNull EncodeCodecProfile codecProfile,
    @NonNull Optional<VideoCodecTier> tier,
    int level,
    @NonNull String codecString) {

  public static VideoEncodeDecision of(@NonNull EncodedVideoOutput output) {
    return allowing(output).orElseThrow(() -> noLevelAllows(output));
  }

  /** The decision whose level is the smallest that allows the output, or empty when none does. */
  static Optional<VideoEncodeDecision> allowing(EncodedVideoOutput output) {
    return switch (output.codecFamily()) {
      case "h264" -> H264Level.smallestAllowing(output).map(VideoEncodeDecision::h264);
      case "av1" -> Av1Level.smallestAllowing(output).map(VideoEncodeDecision::av1);
      default ->
          throw new UnsupportedVideoEncodeException(
              "The server has no level table for a " + output.codecFamily() + " encode");
    };
  }

  private static VideoEncodeDecision h264(H264Level level) {
    return VideoEncodeDecision.builder()
        .codecProfile(EncodeCodecProfile.H264_HIGH)
        .tier(Optional.empty())
        .level(level.levelIdc())
        .codecString(level.highProfileCodecString())
        .build();
  }

  private static VideoEncodeDecision av1(Av1Level level) {
    return VideoEncodeDecision.builder()
        .codecProfile(EncodeCodecProfile.AV1_MAIN)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(level.level())
        .codecString(level.mainProfileCodecString())
        .build();
  }

  private static UnsupportedVideoEncodeException noLevelAllows(EncodedVideoOutput output) {
    return new UnsupportedVideoEncodeException(
        String.format(
            Locale.ROOT,
            "No %s level allows %dx%d at %s fps and %d b/s",
            output.codecFamily(),
            output.width(),
            output.height(),
            output.frameRate(),
            output.bitrate()));
  }
}
