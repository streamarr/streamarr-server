package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;

@Builder
public record StreamInfo(
    int index,
    String codecType,
    String codec,
    Optional<String> language,
    OptionalInt channels,
    OptionalLong bitrate,
    boolean isDefault,
    boolean isForced) {

  public StreamInfo {
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
