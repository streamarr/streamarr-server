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
   * The size that FFmpeg scales an encode from, and that a player shows. FFmpeg crops the coded
   * picture first and then turns it a quarter turn for a rotation of 90 or -90 degrees. It applies
   * no other rotation before scaling. Its crop filter rounds the size down to whole chroma samples.
   * A crop that leaves no picture leaves the coded size.
   */
  public PictureSize displayedSize(@NonNull PictureSize coded) {
    var cropped = containerCropping.flatMap(crop -> cropped(coded, crop)).orElse(coded);
    if (rotationDegrees.isEmpty() || Math.abs(rotationDegrees.getAsInt()) != 90) {
      return cropped;
    }

    return new PictureSize(cropped.height(), cropped.width());
  }

  private Optional<PictureSize> cropped(PictureSize coded, ContainerCropping crop) {
    var width = (long) coded.width() - crop.left() - crop.right();
    var height = (long) coded.height() - crop.top() - crop.bottom();
    if (sharesChromaAcrossColumns()) {
      width -= width % 2;
    }

    if (sharesChromaAcrossRows()) {
      height -= height % 2;
    }

    if (width <= 0 || height <= 0) {
      return Optional.empty();
    }

    return Optional.of(new PictureSize((int) width, (int) height));
  }

  // A stream without a known chroma subsampling crops as 4:2:0, the subsampling of nearly every
  // source.
  private boolean sharesChromaAcrossColumns() {
    return chromaSubsampling
        .map(
            subsampling ->
                subsampling == ChromaSubsampling.CHROMA_420
                    || subsampling == ChromaSubsampling.CHROMA_422)
        .orElse(true);
  }

  private boolean sharesChromaAcrossRows() {
    return chromaSubsampling
        .map(subsampling -> subsampling == ChromaSubsampling.CHROMA_420)
        .orElse(true);
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
