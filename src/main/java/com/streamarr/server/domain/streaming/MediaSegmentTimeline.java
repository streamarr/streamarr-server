package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * The zero-based grid of media segments a variant's playlist advertises: segment {@code N} covers
 * media time {@code [N × target, (N + 1) × target)}, where the target segment duration counts in
 * whole seconds, and the last segment ends at the media duration, counted in whole milliseconds.
 */
public record MediaSegmentTimeline(
    @NonNull Duration mediaDuration, @NonNull Duration targetSegmentDuration) {

  public int targetSegmentDurationSeconds() {
    return (int) targetSegmentDuration.toSeconds();
  }

  public int mediaSegmentCount() {
    return Math.toIntExact(
        Math.ceilDiv(mediaDuration.toMillis(), targetSegmentDurationSeconds() * 1000L));
  }
}
