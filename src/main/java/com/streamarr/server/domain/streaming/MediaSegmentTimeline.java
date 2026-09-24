package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * The zero-based grid of media segments a variant's playlist advertises: segment {@code N} covers
 * media time {@code [N × period, (N + 1) × period)}, and the last segment ends at the media
 * duration, counted in whole milliseconds.
 */
public record MediaSegmentTimeline(@NonNull Duration mediaDuration, int periodSeconds) {

  public int mediaSegmentCount() {
    return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), periodSeconds * 1000L));
  }
}
