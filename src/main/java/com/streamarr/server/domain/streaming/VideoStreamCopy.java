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

  // ADR 0037's frame-rate rule snaps the rate that the source's frames run at to a listed rate,
  // which only an encode runs at. A copy keeps every source frame at that unsnapped rate, except
  // that the rule counts a rate from 60 to 60.3 fps as 60.
  private static final double RATE_60 = 60;
  private static final double FASTEST_RATE_COUNTED_AS_60 = 60.3;
  // The rule's fallback passes over a declared rate of 1000 fps or faster as unusable.
  private static final double FASTEST_USABLE_DECLARED_RATE = 1000;

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
    var outputFrameRate = rule.outputFrameRate().map(Fraction::toDouble);
    var sourceRate =
        switch (rule.basis()) {
          case PACKET_SCAN -> motionRate(rule.inputs());
          case DECLARED_RATE_FALLBACK -> usableDeclaredRate(rule.inputs());
          case DECLARED_RATES, MATROSKA_STATISTICS, REFUSED -> Optional.<Double>empty();
        };
    return sourceRate
        .map(VideoStreamCopy::countedAs60)
        .or(() -> outputFrameRate)
        .map(OptionalDouble::of)
        .orElse(OptionalDouble.empty());
  }

  private static Optional<Double> motionRate(FrameRateRuleInputs inputs) {
    return inputs
        .packetScan()
        .map(FrameRatePacketScan::motionRate)
        .filter(OptionalDouble::isPresent)
        .map(OptionalDouble::getAsDouble);
  }

  // The rule's fallback snaps the average rate, or the base rate when the average rate is not
  // usable.
  private static Optional<Double> usableDeclaredRate(FrameRateRuleInputs inputs) {
    return inputs
        .averageRate()
        .map(Fraction::toDouble)
        .filter(VideoStreamCopy::isUsableDeclaredRate)
        .or(
            () ->
                inputs
                    .baseRate()
                    .map(Fraction::toDouble)
                    .filter(VideoStreamCopy::isUsableDeclaredRate));
  }

  private static boolean isUsableDeclaredRate(double rate) {
    return rate < FASTEST_USABLE_DECLARED_RATE;
  }

  private static double countedAs60(double rate) {
    if (rate > RATE_60 && rate <= FASTEST_RATE_COUNTED_AS_60) {
      return RATE_60;
    }

    return rate;
  }

  // A probe that could not read a width or a height stores zero for it.
  private static Optional<PictureSize> decodedSize(MediaProbe source) {
    if (source.width() <= 0 || source.height() <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize(source.width(), source.height()));
  }
}
