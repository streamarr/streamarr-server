package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProbeContainer(
    @NonNull Optional<String> format,
    @NonNull Optional<Duration> duration,
    @NonNull OptionalLong bitrate) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class ProbeContainerBuilder {
    private Optional<String> format = Optional.empty();
    private Optional<Duration> duration = Optional.empty();
    private OptionalLong bitrate = OptionalLong.empty();
  }
}
