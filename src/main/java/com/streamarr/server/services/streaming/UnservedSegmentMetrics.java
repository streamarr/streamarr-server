package com.streamarr.server.services.streaming;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Counts the media segments that the server stored and removed without answering a viewer's request
 * with them, under one metric name. The {@code cause} label names why the server removed them. Only
 * a cause with a method here counts.
 */
@Component
public class UnservedSegmentMetrics {

  private static final String UNSERVED_SEGMENTS = "streamarr.streaming.unserved_segments";

  private final Counter afterSessionEnded;
  private final Counter afterDiskPressureEviction;
  private final Counter afterSessionStartFailed;
  private final Counter atServerShutdown;

  public UnservedSegmentMetrics(@NonNull MeterRegistry meterRegistry) {
    afterSessionEnded = unservedSegmentsRemovedFor("session_ended", meterRegistry);
    afterDiskPressureEviction = unservedSegmentsRemovedFor("disk_pressure_eviction", meterRegistry);
    afterSessionStartFailed = unservedSegmentsRemovedFor("session_start_failed", meterRegistry);
    atServerShutdown = unservedSegmentsRemovedFor("server_shutdown", meterRegistry);
  }

  private static Counter unservedSegmentsRemovedFor(String cause, MeterRegistry meterRegistry) {
    return Counter.builder(UNSERVED_SEGMENTS)
        .description(
            "Stored media segments that the server removed before it answered a viewer's request"
                + " with them, by the cause of the removal")
        .tag("cause", cause)
        .register(meterRegistry);
  }

  /** A stream session ended and the server deleted its stored segments. */
  void countAfterSessionEnded(int segments) {
    afterSessionEnded.increment(segments);
  }

  /** The server ended an idle stream session early because the segment folder's disk ran short. */
  void countAfterDiskPressureEviction(int segments) {
    afterDiskPressureEviction.increment(segments);
  }

  /** A stream session's start failed after a job attempt had stored segments. */
  void countAfterSessionStartFailed(int segments) {
    afterSessionStartFailed.increment(segments);
  }

  /** The server stopped while a stream session still stored segments. */
  void countAtServerShutdown(int segments) {
    atServerShutdown.increment(segments);
  }
}
