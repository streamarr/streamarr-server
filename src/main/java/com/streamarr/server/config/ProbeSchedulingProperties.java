package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keyframePacketScanSchedulerThreads how many keyframe packet scans one server runs at once
 * @param resultCheckInterval how often a scan that waits for its probes checks their stored results
 */
@ConfigurationProperties(prefix = "probe")
public record ProbeSchedulingProperties(
    Duration busyWorkerRetryDelay,
    Duration resultCheckInterval,
    Integer keyframePacketScanSchedulerThreads) {

  private static final Duration DEFAULT_BUSY_WORKER_RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);
  private static final int DEFAULT_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS = 16;

  public ProbeSchedulingProperties {
    if (keyframePacketScanSchedulerThreads == null) {
      keyframePacketScanSchedulerThreads = DEFAULT_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS;
    }

    if (keyframePacketScanSchedulerThreads < 1) {
      throw new IllegalArgumentException("Keyframe packet scan scheduler threads must be positive");
    }

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
