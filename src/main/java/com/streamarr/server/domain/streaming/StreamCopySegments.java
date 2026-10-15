package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * Where a stream copy's media segments start under ADR 0037's boundary rule, or why a stream copy
 * of the source cannot deliver every media segment.
 */
public sealed interface StreamCopySegments {

  /**
   * Segment N opens at the keyframe at index N, whose times count ticks of the keyframe positions'
   * time base. The last segment holds the source's last keyframe.
   */
  record Starts(@NonNull List<KeyframePosition> openingKeyframes) implements StreamCopySegments {

    public Starts {
      openingKeyframes = List.copyOf(openingKeyframes);
    }
  }

  /** The segment's interval holds no keyframe, so the producer would skip its segment number. */
  record IntervalWithoutKeyframe(long segmentNumber) implements StreamCopySegments {}

  /**
   * The keyframe at this index presents before the keyframe decoded before it, which the producer
   * refuses.
   */
  record PresentationTimeRegressed(int keyframeIndex) implements StreamCopySegments {}

  /**
   * The keyframe at this index lies too far from zero to place in a segment: its presentation time
   * times the time base numerator overflows a {@code long}. No real source reaches such a time.
   */
  record PresentationTimeOutOfRange(int keyframeIndex) implements StreamCopySegments {}

  /**
   * The keyframe at this index opens a segment, but its decode time lies too far from zero for a
   * copy attempt to seek to: that time times the time base numerator overflows a {@code long}.
   */
  record DecodeTimeOutOfRange(int keyframeIndex) implements StreamCopySegments {}
}
