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
    return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), targetSegmentMillis()));
  }

  public boolean advertises(int index) {
    return index >= 0 && index < mediaSegmentCount();
  }

  public int mediaSegmentStartSeconds(int index) {
    return index * targetSegmentDurationSeconds();
  }

  public Duration mediaSegmentDuration(int index) {
    var remainingMillis = mediaDuration.toMillis() - index * targetSegmentMillis();
    return Duration.ofMillis(Math.min(targetSegmentMillis(), remainingMillis));
  }

  private long targetSegmentMillis() {
    return targetSegmentDurationSeconds() * 1000L;
  }
}
