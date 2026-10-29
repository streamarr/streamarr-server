package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The video that a stream copy of the chosen video stream would deliver, described by the probe
 * outcome's facts. {@code videoProperties} is empty for a probe version 4 outcome, which stores no
 * codec profile, codec level or codec tier. {@code decodedSize} is the size of the frames that the
 * stream carries, before the container's cropping and the rotation, and is empty when the probe
 * could not read a width or a height. {@code frameRate} is the output frame rate, empty when the
 * frame-rate rule refused one.
 */
@Builder
public record VideoStreamCopy(
    @NonNull ProbedCodecFamily codecFamily,
    @NonNull Optional<VideoStreamProperties> videoProperties,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<PictureSize> decodedSize,
    @NonNull Optional<Fraction> frameRate,
    @NonNull VideoDynamicRange dynamicRange) {

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
                    .frameRate(facts.frameRateRule().outputFrameRate())
                    .dynamicRange(facts.dynamicRange())
                    .build());
  }

  // A probe that could not read a width or a height stores zero for it.
  private static Optional<PictureSize> decodedSize(MediaProbe source) {
    if (source.width() <= 0 || source.height() <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize(source.width(), source.height()));
  }
}
