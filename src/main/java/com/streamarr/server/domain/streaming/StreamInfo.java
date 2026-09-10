package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

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
    boolean isForced) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class StreamInfoBuilder {
    private Optional<String> codec = Optional.empty();
    private Optional<String> language = Optional.empty();
    private OptionalInt channels = OptionalInt.empty();
    private OptionalLong bitrate = OptionalLong.empty();
    private OptionalInt width = OptionalInt.empty();
    private OptionalInt height = OptionalInt.empty();
    private OptionalDouble framerate = OptionalDouble.empty();
  }
}
