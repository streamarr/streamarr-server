package com.streamarr.server.services.library;

import java.time.Duration;
import java.time.Instant;

/** Allows one progress report per interval, the first one interval after the start. */
final class ProgressReportInterval {

  private final Duration interval;
  private Instant nextReportAt;

  ProgressReportInterval(Duration interval, Instant start) {
    this.interval = interval;
    nextReportAt = start.plus(interval);
  }

  /** Whether a report is due at {@code now}. A due report starts the next interval. */
  boolean tryTakeReport(Instant now) {
    if (now.isBefore(nextReportAt)) {
      return false;
    }

    nextReportAt = now.plus(interval);
    return true;
  }
}
