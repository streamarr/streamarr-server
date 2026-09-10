package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;

@Builder
public record ProbeContainer(
    Optional<String> format, Optional<Duration> duration, OptionalLong bitrate) {

  public ProbeContainer {
    format = format == null ? Optional.empty() : format;
    duration = duration == null ? Optional.empty() : duration;
    bitrate = bitrate == null ? OptionalLong.empty() : bitrate;
  }
}
