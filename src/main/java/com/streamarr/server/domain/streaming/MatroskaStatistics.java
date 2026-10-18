package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * Matroska's per-stream statistics tags, NUMBER_OF_FRAMES and DURATION. A stream without either tag
 * has no statistics.
 */
public record MatroskaStatistics(
    @NonNull OptionalLong numberOfFrames, @NonNull Optional<Duration> duration) {

  public MatroskaStatistics {
    if (numberOfFrames.isEmpty() && duration.isEmpty()) {
      throw new IllegalArgumentException("Matroska statistics need at least one tag");
    }
  }
}
