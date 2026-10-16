package com.streamarr.server.services.streaming.remote;

import java.time.Duration;
import lombok.NonNull;

/**
 * How the server detects a dead worker connection: after {@code time} without reading from the
 * worker it pings the worker, and it closes the connection when no acknowledgement arrives within
 * {@code timeout}.
 */
public record WorkerSessionKeepalive(@NonNull Duration time, @NonNull Duration timeout) {

  static final WorkerSessionKeepalive DEFAULT =
      new WorkerSessionKeepalive(Duration.ofSeconds(30), Duration.ofSeconds(10));
}
