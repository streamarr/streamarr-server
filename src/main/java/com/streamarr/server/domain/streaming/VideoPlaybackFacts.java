package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The facts of the chosen video stream that probe version 4 and later report for playback. A
 * transcode decision reads them, and the job carries the stream index, dynamic range and codec
 * string to the worker as ADR 0036's source description.
 */
@Builder
public record VideoPlaybackFacts(
    int streamIndex,
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<ChromaSubsampling> chromaSubsampling,
    @NonNull Optional<String> codecString) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class VideoPlaybackFactsBuilder {
    private VideoDynamicRange dynamicRange = VideoDynamicRange.UNSPECIFIED;
    private OptionalInt bitDepth = OptionalInt.empty();
    private Optional<ChromaSubsampling> chromaSubsampling = Optional.empty();
    private Optional<String> codecString = Optional.empty();
  }
}
