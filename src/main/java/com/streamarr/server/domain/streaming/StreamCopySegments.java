package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * Where a stream copy's media segments start under ADR 0037's boundary rule, or why the server
 * cannot place the source's keyframes in a stream copy's media segments.
 */
public sealed interface StreamCopySegments {

  /**
   * Segment N opens at the keyframe whose index in the keyframe positions is {@code
   * openingKeyframeIndexes[N]}, the first keyframe in its first slot. The last segment holds the
   * source's last keyframe. Empty when no keyframe presents at or after zero.
   */
  record Starts(@NonNull List<Integer> openingKeyframeIndexes) implements StreamCopySegments {

    public Starts {
      openingKeyframeIndexes = List.copyOf(openingKeyframeIndexes);
    }
  }

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
