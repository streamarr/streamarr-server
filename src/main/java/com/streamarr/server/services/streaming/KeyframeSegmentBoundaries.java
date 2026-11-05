package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.StreamCopySegments;
import java.util.ArrayList;
import lombok.NonNull;

/**
 * Applies ADR 0037's boundary rule to a source's keyframe positions, as the worker's producer
 * applies it to a stream copy on the stream session's segment list. Slot {@code k} covers {@code [k
 * × period, (k + 1) × period)} of media time. The first keyframe that presents at or after zero
 * opens segment 0, and each later slot's first keyframe opens the next segment. A slot without a
 * keyframe merges into the segment before it.
 */
final class KeyframeSegmentBoundaries {

  private KeyframeSegmentBoundaries() {}

  static StreamCopySegments forStreamCopy(@NonNull KeyframePositions positions, int periodSeconds) {
    if (periodSeconds <= 0) {
      throw new IllegalArgumentException("Period must be positive, got: " + periodSeconds);
    }

    var keyframes = positions.keyframes();
    var openingKeyframes = new ArrayList<Integer>();
    var openSlot = -1L;
    for (var index = 0; index < keyframes.size(); index++) {
      var presentationTime = keyframes.get(index).presentationTime();
      if (index > 0 && presentationTime < keyframes.get(index - 1).presentationTime()) {
        return new StreamCopySegments.PresentationTimeRegressed(index);
      }

      if (isOutOfRange(presentationTime, positions.timeBaseNumerator())) {
        return new StreamCopySegments.PresentationTimeOutOfRange(index);
      }

      // A keyframe in the open segment's slot joins that segment, and one before zero opens none.
      var slot = slotOf(presentationTime, positions, periodSeconds);
      if (slot <= openSlot || slot < 0) {
        continue;
      }

      if (isOutOfRange(keyframes.get(index).decodeTime(), positions.timeBaseNumerator())) {
        return new StreamCopySegments.DecodeTimeOutOfRange(index);
      }

      openingKeyframes.add(index);
      openSlot = slot;
    }

    return new StreamCopySegments.Starts(openingKeyframes);
  }

  private static boolean isOutOfRange(long ticks, int timeBaseNumerator) {
    return ticks > Long.MAX_VALUE / timeBaseNumerator || ticks < Long.MIN_VALUE / timeBaseNumerator;
  }

  private static long slotOf(
      long presentationTime, KeyframePositions positions, int periodSeconds) {
    return Math.floorDiv(
        Math.multiplyExact(presentationTime, positions.timeBaseNumerator()),
        (long) periodSeconds * positions.timeBaseDenominator());
  }
}
