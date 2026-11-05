package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * Where each media segment of a stream copy's segment list starts on the zero-based timeline, in
 * ticks of {@code timeBase}, the time base of the source video stream's stored keyframe positions.
 * Segment 0 starts at zero, and every later segment at the stored presentation tick of the keyframe
 * that opens it, so no start is rounded (ADR 0037, segment list).
 */
public record ExactSegmentStarts(@NonNull List<Long> ticks, @NonNull Fraction timeBase) {

  public ExactSegmentStarts {
    ticks = List.copyOf(ticks);
    if (ticks.isEmpty() || ticks.getFirst() != 0) {
      throw new IllegalArgumentException("A segment list starts its first segment at zero");
    }

    for (var index = 1; index < ticks.size(); index++) {
      if (ticks.get(index) <= ticks.get(index - 1)) {
        throw new IllegalArgumentException(
            "Segment " + index + " must start after segment " + (index - 1));
      }
    }
  }
}
