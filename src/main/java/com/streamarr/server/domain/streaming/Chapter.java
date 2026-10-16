package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * One chapter of a source's container, as the probe reported it. Both times are on the zero-based
 * media timeline of the keyframe positions and the segment grid: the demuxer time minus the
 * container's start time. A chapter that starts before the container's start time therefore has a
 * negative start time.
 */
@Builder
public record Chapter(
    @NonNull Optional<Duration> startTime,
    @NonNull Optional<Duration> endTime,
    @NonNull Optional<String> title) {

  /** The longest title, in bytes of UTF-8, that a probe reports. */
  public static final int MAXIMUM_TITLE_BYTES = 512;

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class ChapterBuilder {
    private Optional<Duration> startTime = Optional.empty();
    private Optional<Duration> endTime = Optional.empty();
    private Optional<String> title = Optional.empty();
  }
}
