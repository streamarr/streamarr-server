package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import java.util.ArrayList;
import lombok.NonNull;

/**
 * Applies ADR 0037's boundary rule to a source's keyframe positions, as the worker's producer
 * applies it to a stream copy that starts at segment 0. Segment N covers {@code [N × period, (N +
 * 1) × period)} and opens at the first keyframe whose presentation time lies inside that interval.
 */
final class KeyframeSegmentBoundaries {

  private KeyframeSegmentBoundaries() {}

  static StreamCopySegments forStreamCopy(@NonNull KeyframePositions positions, int periodSeconds) {
    if (periodSeconds <= 0) {
      throw new IllegalArgumentException("Period must be positive, got: " + periodSeconds);
    }

    var keyframes = positions.keyframes();
    var starts = new ArrayList<Long>();
    for (var index = 0; index < keyframes.size(); index++) {
      var presentationTime = keyframes.get(index).presentationTime();
      if (index > 0 && presentationTime < keyframes.get(index - 1).presentationTime()) {
        return new StreamCopySegments.PresentationTimeRegressed(index);
      }

      var segmentNumber = segmentNumberOf(presentationTime, positions, periodSeconds);
      if (segmentNumber > starts.size()) {
        return new StreamCopySegments.IntervalWithoutKeyframe(starts.size());
      }

      // A lower number joins the open segment, or lies before zero, where no segment opens.
      if (segmentNumber == starts.size()) {
        starts.add(presentationTime);
      }
    }

    if (starts.isEmpty()) {
      return new StreamCopySegments.IntervalWithoutKeyframe(0);
    }

    return new StreamCopySegments.Starts(starts);
  }

  private static long segmentNumberOf(
      long presentationTime, KeyframePositions positions, int periodSeconds) {
    return Math.floorDiv(
        Math.multiplyExact(presentationTime, positions.timeBaseNumerator()),
        (long) periodSeconds * positions.timeBaseDenominator());
  }
}
