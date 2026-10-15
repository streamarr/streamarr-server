package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.NonNull;

/**
 * The media segments a variant's playlist advertises, numbered from zero on the grid of the target
 * segment duration: segment {@code N}'s interval starts at {@code N × target}, where the target
 * counts in whole seconds.
 */
public sealed interface MediaSegmentTimeline {

  int targetSegmentDurationSeconds();

  int mediaSegmentCount();

  Duration mediaSegmentDuration(int index);

  default boolean advertises(int index) {
    return index >= 0 && index < mediaSegmentCount();
  }

  default int mediaSegmentStartSeconds(int index) {
    return index * targetSegmentDurationSeconds();
  }

  /**
   * Segment {@code N} covers media time {@code [N × target, (N + 1) × target)}, and the last
   * segment ends at the media duration, counted in whole milliseconds.
   */
  record PlainGrid(@NonNull Duration mediaDuration, @NonNull Duration targetSegmentDuration)
      implements MediaSegmentTimeline {

    @Override
    public int targetSegmentDurationSeconds() {
      return (int) targetSegmentDuration.toSeconds();
    }

    @Override
    public int mediaSegmentCount() {
      return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), targetSegmentMillis()));
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var remainingMillis = mediaDuration.toMillis() - index * targetSegmentMillis();
      return Duration.ofMillis(Math.min(targetSegmentMillis(), remainingMillis));
    }

    private long targetSegmentMillis() {
      return targetSegmentDurationSeconds() * 1000L;
    }
  }
}
