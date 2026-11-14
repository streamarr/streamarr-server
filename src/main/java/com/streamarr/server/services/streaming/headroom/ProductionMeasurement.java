package com.streamarr.server.services.streaming.headroom;

import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * One media segment's production time, which the worker reports with the segment's upload, and the
 * other job attempts that encoded video on the worker while it produced the segment.
 */
@Builder
public record ProductionMeasurement(
    @NonNull JobAttemptLoad load,
    @NonNull List<JobAttemptLoad> otherEncodes,
    @NonNull Duration mediaDuration,
    @NonNull Duration productionTime) {

  public ProductionMeasurement {
    otherEncodes = List.copyOf(otherEncodes);
    if (!mediaDuration.isPositive() || !productionTime.isPositive()) {
      throw new IllegalArgumentException(
          "A measurement needs positive durations: " + mediaDuration + " in " + productionTime);
    }
  }

  /** Media seconds produced per second of production time. */
  double productionSpeed() {
    return (double) mediaDuration.toNanos() / productionTime.toNanos();
  }
}
