package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resultCheckInterval how often a scan that waits for its probes checks their stored results
 */
@ConfigurationProperties(prefix = "probe")
public record ProbeSchedulingProperties(
    Duration busyWorkerRetryDelay, Duration resultCheckInterval) {

  private static final Duration DEFAULT_BUSY_WORKER_RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);

  public ProbeSchedulingProperties {
    if (busyWorkerRetryDelay == null) {
      busyWorkerRetryDelay = DEFAULT_BUSY_WORKER_RETRY_DELAY;
    }

    if (!busyWorkerRetryDelay.isPositive()) {
      throw new IllegalArgumentException("Busy worker retry delay must be positive");
    }

    if (resultCheckInterval == null) {
      resultCheckInterval = DEFAULT_RESULT_CHECK_INTERVAL;
    }

    if (!resultCheckInterval.isPositive()) {
      throw new IllegalArgumentException("Probe result check interval must be positive");
    }
  }
}
