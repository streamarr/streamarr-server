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
    @NonNull VideoCodecProfile codecProfile,
    @NonNull Optional<VideoCodecTier> tier,
    int level,
    @NonNull String codecString) {

  public static VideoEncodeDecision of(@NonNull EncodedVideoOutput output) {
    return switch (output.codecFamily()) {
      case "h264" -> h264(output);
      case "av1" -> av1(output);
      default ->
          throw new UnsupportedVideoEncodeException(
              "The server has no level table for a " + output.codecFamily() + " encode");
    };
  }

  private static VideoEncodeDecision h264(EncodedVideoOutput output) {
    var level =
        H264Level.smallestAllowing(output).orElseThrow(() -> noLevelAllows(output, "H.264"));
    return VideoEncodeDecision.builder()
        .codecProfile(VideoCodecProfile.H264_HIGH)
        .tier(Optional.empty())
        .level(level.levelIdc())
        .codecString(level.highProfileCodecString())
        .build();
  }

  private static VideoEncodeDecision av1(EncodedVideoOutput output) {
    var level = Av1Level.smallestAllowing(output).orElseThrow(() -> noLevelAllows(output, "AV1"));
    return VideoEncodeDecision.builder()
        .codecProfile(VideoCodecProfile.AV1_MAIN)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(level.level())
        .codecString(level.mainProfileCodecString())
        .build();
  }

  private static UnsupportedVideoEncodeException noLevelAllows(
      EncodedVideoOutput output, String codec) {
    return new UnsupportedVideoEncodeException(
        String.format(
            Locale.ROOT,
            "No %s level allows %dx%d at %s fps and %d b/s",
            codec,
            output.width(),
            output.height(),
            output.frameRate(),
            output.bitrate()));
  }
}
