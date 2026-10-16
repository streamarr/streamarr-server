package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resultCheckInterval how often a scan that waits for its probes checks their stored results
 */
@ConfigurationProperties(prefix = "probe")
public record ProbeSchedulingProperties(
    Duration busyWorkerRetryDelay,
    Duration resultCheckInterval,
    Integer maxConcurrentKeyframePacketScans) {

  private static final Duration DEFAULT_BUSY_WORKER_RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);
  // Keyframe packet scans use the workers' probe capacity, like probes. One scan at a time leaves
  // the rest of the probe capacity to probes.
  private static final int DEFAULT_MAX_CONCURRENT_KEYFRAME_PACKET_SCANS = 1;

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

    if (maxConcurrentKeyframePacketScans == null) {
      maxConcurrentKeyframePacketScans = DEFAULT_MAX_CONCURRENT_KEYFRAME_PACKET_SCANS;
    }

    if (maxConcurrentKeyframePacketScans <= 0) {
      throw new IllegalArgumentException("The keyframe packet scan maximum must be positive");
    }
  }
}
