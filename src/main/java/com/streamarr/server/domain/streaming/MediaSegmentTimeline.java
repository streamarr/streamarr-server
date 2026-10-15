package com.streamarr.server.domain.streaming;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * The media segments a variant's playlist advertises, numbered from zero on the grid of the target
 * segment duration: segment {@code N}'s interval starts at {@code N × target}, where the target
 * counts in whole seconds.
 */
public sealed interface MediaSegmentTimeline {

  Duration targetSegmentDuration();

  int mediaSegmentCount();

  Duration mediaSegmentDuration(int index);

  default int targetSegmentDurationSeconds() {
    return (int) targetSegmentDuration().toSeconds();
  }

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
    public int mediaSegmentCount() {
      return Math.toIntExact(Math.ceilDiv(mediaDuration.toMillis(), targetSegmentMillis()));
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var remainingMillis = mediaDuration.toMillis() - index * targetSegmentMillis();
      return Duration.ofMillis(Math.min(targetSegmentMillis(), remainingMillis));
    }

    /**
     * This grid's segments as a stream copy cuts them at the source keyframes that open each one. A
     * keyframe that presents at or after the media end opens no segment.
     */
    public StreamCopy openedAt(
        @NonNull KeyframePositions positions, StreamCopySegments.@NonNull Starts starts) {
      var mediaEnd = Duration.ofMillis(mediaDuration.toMillis());
      var segmentStarts = new ArrayList<Duration>();
      for (var keyframe : starts.openingKeyframes()) {
        var start = positions.timeOf(keyframe.presentationTime(), RoundingMode.FLOOR);
        if (start.compareTo(mediaEnd) >= 0) {
          break;
        }

        // Segment 0 also holds the media that presents before its keyframe, from zero.
        if (segmentStarts.isEmpty()) {
          start = Duration.ZERO;
        }

        segmentStarts.add(start);
      }

      return new StreamCopy(mediaEnd, targetSegmentDuration, segmentStarts);
    }

    private long targetSegmentMillis() {
      return targetSegmentDurationSeconds() * 1000L;
    }
  }

  /**
   * A stream copy's segments, which open at source keyframes under ADR 0037's boundary rule.
   * Segment {@code N} runs from its start up to the next segment's start, and the last segment ends
   * at the media duration.
   */
  record StreamCopy(
      @NonNull Duration mediaDuration,
      @NonNull Duration targetSegmentDuration,
      @NonNull List<Duration> segmentStarts)
      implements MediaSegmentTimeline {

    public StreamCopy {
      segmentStarts = List.copyOf(segmentStarts);
    }

    @Override
    public int mediaSegmentCount() {
      return segmentStarts.size();
    }

    @Override
    public Duration mediaSegmentDuration(int index) {
      var end = mediaDuration;
      if (index + 1 < segmentStarts.size()) {
        end = segmentStarts.get(index + 1);
      }

      return end.minus(segmentStarts.get(index));
    }
  }
}
