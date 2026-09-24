package com.streamarr.server.services.streaming.remote;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reads the counter of initialization segment uploads refused for differing from the stored one.
 */
final class InitializationSegmentMismatchMetric {

  private static final String NAME = "streamarr.streaming.initialization_segment_mismatches";

  private InitializationSegmentMismatchMetric() {}

  static double count(MeterRegistry meterRegistry) {
    return meterRegistry.get(NAME).counter().count();
  }
}
