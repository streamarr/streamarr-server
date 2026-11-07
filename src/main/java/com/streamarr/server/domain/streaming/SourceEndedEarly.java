package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * A completed job attempt's report that the source ran out before the variant's last advertised
 * media segment (ADR 0037, early ends): the last media segment that holds a source sample, and the
 * chosen source streams that still have samples after that segment's interval, which no delivered
 * segment carries.
 */
public record SourceEndedEarly(
    int lastSegmentWithSourceMedia, @NonNull List<Integer> streamIndexesWithMediaAfter) {

  public SourceEndedEarly {
    streamIndexesWithMediaAfter = List.copyOf(streamIndexesWithMediaAfter);
  }

  /** Whether every chosen source stream had ended by the end of the last segment. */
  public boolean everyStreamEnded() {
    return streamIndexesWithMediaAfter.isEmpty();
  }
}
