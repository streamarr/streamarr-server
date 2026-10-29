package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The video that a stream copy of the chosen video stream would deliver, described by the probe
 * outcome's facts. {@code videoProperties} is empty for a probe version 4 outcome, which stores no
 * codec profile, codec level or codec tier. {@code decodedSize} is the size of the frames that a
 * decoder outputs, before the container's cropping and the rotation, because decoder limits bound
 * decoded frames. It is empty when the probe could not read a width or a height. {@code frameRate}
 * is the rate at which the copied frames run, empty when the frame-rate rule refused one.
 */
@Builder
public record VideoStreamCopy(
    @NonNull ProbedCodecFamily codecFamily,
    @NonNull Optional<VideoStreamProperties> videoProperties,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<PictureSize> decodedSize,
    @NonNull OptionalDouble frameRate,
    @NonNull VideoDynamicRange dynamicRange) {

  // ADR 0037's frame-rate rule divides a motion rate above 60.3 fps down to a listed rate, which
  // only an encode runs at. A copy keeps every source frame, so it runs at that motion rate. At or
  // below 60.3 fps the rule only rounds the motion rate to the nearest listed rate.
  private static final double FASTEST_MOTION_RATE_COUNTED_AS_60 = 60.3;

  /** The copy of the source's chosen video stream, or empty without a codec or playback facts. */
  public static Optional<VideoStreamCopy> of(@NonNull MediaProbe source) {
    if (source.videoCodec() == null) {
      return Optional.empty();
    }

    var codecFamily = ProbedCodecFamily.fromProbedName(source.videoCodec());
    return source
        .videoPlaybackFacts()
        .map(
            facts ->
                VideoStreamCopy.builder()
                    .codecFamily(codecFamily)
                    .videoProperties(facts.videoProperties())
                    .bitDepth(facts.bitDepth())
                    .decodedSize(decodedSize(source))
                    .frameRate(frameRate(facts.frameRateRule()))
                    .dynamicRange(facts.dynamicRange())
                    .build());
  }

  private static OptionalDouble frameRate(FrameRateRule rule) {
    var motionRate =
        rule.inputs()
            .packetScan()
            .map(FrameRatePacketScan::motionRate)
            .orElse(OptionalDouble.empty());
    if (motionRate.isPresent() && motionRate.getAsDouble() > FASTEST_MOTION_RATE_COUNTED_AS_60) {
      return motionRate;
    }

    return rule.outputFrameRate()
        .map(rate -> OptionalDouble.of(rate.toDouble()))
        .orElse(OptionalDouble.empty());
  }

  // A probe that could not read a width or a height stores zero for it.
  private static Optional<PictureSize> decodedSize(MediaProbe source) {
    if (source.width() <= 0 || source.height() <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize(source.width(), source.height()));
  }
}
