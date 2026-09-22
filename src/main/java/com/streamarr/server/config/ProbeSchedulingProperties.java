package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "probe")
public record ProbeSchedulingProperties(Duration busyWorkerRetryDelay) {

  private static final Duration DEFAULT_BUSY_WORKER_RETRY_DELAY = Duration.ofSeconds(5);

  public ProbeSchedulingProperties {
    if (busyWorkerRetryDelay == null) {
      busyWorkerRetryDelay = DEFAULT_BUSY_WORKER_RETRY_DELAY;
    }

    if (!busyWorkerRetryDelay.isPositive()) {
      throw new IllegalArgumentException("Busy worker retry delay must be positive");
    }
  }
}
