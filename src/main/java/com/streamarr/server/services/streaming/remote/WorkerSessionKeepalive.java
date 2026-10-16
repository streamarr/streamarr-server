package com.streamarr.server.services.streaming.remote;

import java.time.Duration;
import lombok.NonNull;

/**
 * How the server detects a dead worker connection: after {@code time} without reading from the
 * worker it pings the worker, and it closes the connection when no acknowledgement arrives within
 * {@code timeout}.
 */
public record WorkerSessionKeepalive(@NonNull Duration time, @NonNull Duration timeout) {

  // gRPC raises a shorter keepalive time to this without telling the caller.
  private static final Duration SHORTEST_TIME = Duration.ofSeconds(10);

  static final WorkerSessionKeepalive DEFAULT =
      new WorkerSessionKeepalive(Duration.ofSeconds(30), Duration.ofSeconds(10));

  public WorkerSessionKeepalive {
    if (time.compareTo(SHORTEST_TIME) < 0) {
      throw new IllegalArgumentException("Worker keepalive time must be at least 10 seconds");
    }

    if (!timeout.isPositive()) {
      throw new IllegalArgumentException("Worker keepalive timeout must be positive");
    }
  }
}
