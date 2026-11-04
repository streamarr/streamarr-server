package com.streamarr.server.domain.streaming;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * The video that a stream copy of the chosen video stream would deliver, described by the probe
 * outcome's facts. {@code videoProperties} is empty for a probe version 4 outcome, which stores no
 * codec profile, codec level or codec tier. {@code decodedSize} is the size of the frames that a
 * decoder outputs, before the container's cropping and the rotation, because decoder limits bound
 * decoded frames. It is empty when the probe could not read a width or a height. {@code frameRate}
 * is the rate at which the copied frames run, empty when the frame-rate rule refused one. {@code
 * maximumFrameRate} is the highest rate at which the stream presents pictures anywhere in the file.
 * It is empty when the probe stores no maximum, which holds for every outcome until
 * streamarr/streamarr-server#652. {@code frameRate} is never a maximum. {@code sampleEntry} is the
 * sample entry that an MP4 or MOV file stores for the stream, which direct play delivers as it is,
 * empty in other source containers and before probe version 7.
 */
@Builder(toBuilder = true)
public record VideoStreamCopy(
    @NonNull ProbedCodecFamily codecFamily,
    @NonNull Optional<VideoStreamProperties> videoProperties,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<PictureSize> decodedSize,
    @NonNull OptionalDouble frameRate,
    @NonNull Optional<Fraction> maximumFrameRate,
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull Optional<String> sampleEntry) {

  // ADR 0037's frame-rate rule snaps the rate that the source's frames run at to a listed rate,
  // which only an encode runs at. A copy keeps every source frame at that unsnapped rate.
  // The rule's fallback passes over a declared rate of 1000 fps or faster as unusable.
  private static final double FASTEST_USABLE_DECLARED_RATE = 1000;

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class VideoStreamCopyBuilder {
    private Optional<String> sampleEntry = Optional.empty();
  }

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
                    .maximumFrameRate(Optional.empty())
                    .dynamicRange(facts.dynamicRange())
                    .sampleEntry(facts.sampleEntry())
                    .build());
  }

  /**
   * The Dolby Vision that the playlist describes when this copy keeps it: a profile with a PQ or
   * HLG base that a capability entry can name, at the level that the record names or the level
   * table gives (ADR 0013). Empty for a copy without such Dolby Vision.
   */
  public Optional<DolbyVisionOutput> dolbyVisionOutput() {
    if (CopiedDolbyVision.of(this)
        instanceof CopiedDolbyVision.Declarable(var profile, var level)) {
      return Optional.of(new DolbyVisionOutput(profile, level));
    }

    return Optional.empty();
  }

  /**
   * Whether stripping this copy's Dolby Vision leaves a PQ or HLG base that a player shows (ADR
   * 0036). Only HEVC and AV1 copies strip it. Profiles 5 and 10.0 have no such base, and profiles
   * 8.2 and 9 keep their RPUs under an SDR base.
   */
  public boolean hasDolbyVisionStrippableToAnHdrBase() {
    if (!isStrippableCodecFamily()) {
      return false;
    }

    var base = dynamicRange.compatibleBase();
    if (base != CompatibleBase.PQ && base != CompatibleBase.HLG) {
      return false;
    }

    return switch (CopiedDolbyVision.of(this)) {
      case CopiedDolbyVision.Declarable _, CopiedDolbyVision.Undeclarable _ -> true;
      case CopiedDolbyVision.None _,
          CopiedDolbyVision.KeptUnderSdrBase _,
          CopiedDolbyVision.NeedsDolbyVisionSampleEntry _ ->
          false;
    };
  }

  // The strip removes HEVC NAL units and AV1 metadata OBUs (ADR 0036). H.264 carries only profile
  // 9, which keeps its RPUs under an SDR base.
  private boolean isStrippableCodecFamily() {
    return switch (codecFamily) {
      case HEVC, AV1 -> true;
      case H264, OTHER -> false;
    };
  }

  /** The copy that the worker delivers after the bitstream rewrites (ADR 0036). */
  public VideoStreamCopy afterRewrites(@NonNull Collection<BitstreamRewrite> rewrites) {
    return toBuilder().dynamicRange(dynamicRange.afterRewrites(rewrites)).build();
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
    return Stream.of(inputs.averageRate(), inputs.baseRate())
        .flatMap(Optional::stream)
        .map(Fraction::toDouble)
        .filter(VideoStreamCopy::isUsableDeclaredRate)
        .findFirst();
  }

  private static boolean isUsableDeclaredRate(double rate) {
    return rate < FASTEST_USABLE_DECLARED_RATE;
  }

  // A probe that could not read a width or a height stores zero for it.
  private static Optional<PictureSize> decodedSize(MediaProbe source) {
    if (source.width() <= 0 || source.height() <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize(source.width(), source.height()));
  }
}
