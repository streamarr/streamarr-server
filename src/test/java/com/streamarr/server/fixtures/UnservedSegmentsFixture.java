package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.UnservedSegmentMetrics;
import com.streamarr.server.services.streaming.UnservedSegments;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class UnservedSegmentsFixture {

  private UnservedSegmentsFixture() {}

  /** Unserved segments that count into a meter registry of their own. */
  public static UnservedSegments unservedSegments() {
    return unservedSegmentsCountingInto(new SimpleMeterRegistry());
  }

  public static UnservedSegments unservedSegmentsCountingInto(MeterRegistry meterRegistry) {
    return new UnservedSegments(new UnservedSegmentMetrics(meterRegistry));
  }
}
