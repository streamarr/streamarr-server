package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.UnservedSegmentMetrics;
import com.streamarr.server.services.streaming.UnservedSegments;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class UnservedSegmentsFixture {

  private UnservedSegmentsFixture() {}

  /** Unserved segments of a segment store that count into a meter registry of their own. */
  public static UnservedSegments unservedSegmentsOf(SegmentStore segmentStore) {
    return unservedSegmentsCountingInto(segmentStore, new SimpleMeterRegistry());
  }

  public static UnservedSegments unservedSegmentsCountingInto(
      SegmentStore segmentStore, MeterRegistry meterRegistry) {
    return new UnservedSegments(segmentStore, new UnservedSegmentMetrics(meterRegistry));
  }
}
