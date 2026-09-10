package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;

@Builder
public record StreamInfo(
    int index,
    String codecType,
    Optional<String> codec,
    Optional<String> language,
    OptionalInt channels,
    OptionalLong bitrate,
    OptionalInt width,
    OptionalInt height,
    OptionalDouble framerate,
    boolean isDefault,
    boolean isForced) {

  public StreamInfo {
    codec = codec == null ? Optional.empty() : codec;
    width = width == null ? OptionalInt.empty() : width;
    height = height == null ? OptionalInt.empty() : height;
    framerate = framerate == null ? OptionalDouble.empty() : framerate;
    if (channels == null) {
      channels = OptionalInt.empty();
    }
    if (bitrate == null) {
      bitrate = OptionalLong.empty();
    }
  }

  @SuppressWarnings("java:S1068") // Lombok builder default — field is used by generated code
  public static class StreamInfoBuilder {
    private Optional<String> language = Optional.empty();
  }
}
