package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * One stream of a probed source. The facts from {@code dynamicRange} to {@code audioProfile} come
 * from probe version 4 and later. An earlier probe version leaves them empty.
 */
@Builder
public record StreamInfo(
    int index,
    String codecType,
    @NonNull Optional<String> codec,
    @NonNull Optional<String> language,
    @NonNull OptionalInt channels,
    @NonNull OptionalLong bitrate,
    @NonNull OptionalInt width,
    @NonNull OptionalInt height,
    @NonNull OptionalDouble framerate,
    boolean isDefault,
    boolean isForced,
    @NonNull Optional<VideoDynamicRange> dynamicRange,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<ChromaSubsampling> chromaSubsampling,
    @NonNull OptionalInt rotationDegrees,
    @NonNull Optional<ContainerCropping> containerCropping,
    @NonNull Optional<String> codecString,
    @NonNull OptionalInt sampleRate,
    @NonNull Optional<String> audioProfile) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class StreamInfoBuilder {
    private Optional<String> codec = Optional.empty();
    private Optional<String> language = Optional.empty();
    private OptionalInt channels = OptionalInt.empty();
    private OptionalLong bitrate = OptionalLong.empty();
    private OptionalInt width = OptionalInt.empty();
    private OptionalInt height = OptionalInt.empty();
    private OptionalDouble framerate = OptionalDouble.empty();
    private Optional<VideoDynamicRange> dynamicRange = Optional.empty();
    private OptionalInt bitDepth = OptionalInt.empty();
    private Optional<ChromaSubsampling> chromaSubsampling = Optional.empty();
    private OptionalInt rotationDegrees = OptionalInt.empty();
    private Optional<ContainerCropping> containerCropping = Optional.empty();
    private Optional<String> codecString = Optional.empty();
    private OptionalInt sampleRate = OptionalInt.empty();
    private Optional<String> audioProfile = Optional.empty();
  }
}
