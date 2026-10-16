package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The facts of the chosen video stream that probe version 4 and later report for playback. A
 * transcode decision reads them, and the job carries the stream index, dynamic range and codec
 * string to the worker as ADR 0036's source description. {@code rotationDegrees} is the
 * counterclockwise angle of the stream's display matrix. {@code containerCropping} counts the
 * pixels that the container removes from the picture before the rotation.
 */
@Builder
public record VideoPlaybackFacts(
    int streamIndex,
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<ChromaSubsampling> chromaSubsampling,
    @NonNull Optional<String> codecString,
    @NonNull OptionalInt rotationDegrees,
    @NonNull Optional<ContainerCropping> containerCropping,
    @NonNull FrameRateRule frameRateRule) {

  /**
   * The size that a player shows and that an encode scales from. FFmpeg crops the coded picture
   * first and then turns it a quarter turn for a rotation of 90 or -90 degrees. It applies no other
   * rotation before scaling. A crop that leaves no picture leaves the coded size.
   */
  public PictureSize displayedSize(@NonNull PictureSize coded) {
    var cropped = containerCropping.flatMap(crop -> cropped(coded, crop)).orElse(coded);
    if (rotationDegrees.isEmpty() || Math.abs(rotationDegrees.getAsInt()) != 90) {
      return cropped;
    }

    return new PictureSize(cropped.height(), cropped.width());
  }

  private static Optional<PictureSize> cropped(PictureSize coded, ContainerCropping crop) {
    var width = (long) coded.width() - crop.left() - crop.right();
    var height = (long) coded.height() - crop.top() - crop.bottom();
    if (width <= 0 || height <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize((int) width, (int) height));
  }

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class VideoPlaybackFactsBuilder {
    private VideoDynamicRange dynamicRange = VideoDynamicRange.UNSPECIFIED;
    private OptionalInt bitDepth = OptionalInt.empty();
    private Optional<ChromaSubsampling> chromaSubsampling = Optional.empty();
    private Optional<String> codecString = Optional.empty();
    private OptionalInt rotationDegrees = OptionalInt.empty();
    private Optional<ContainerCropping> containerCropping = Optional.empty();
  }
}
