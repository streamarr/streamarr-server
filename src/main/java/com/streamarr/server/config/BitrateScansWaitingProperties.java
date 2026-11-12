package com.streamarr.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param refreshInterval how often the server counts the bitrate scans that wait in the scan table
 *     for the {@code streamarr.probes.bitrate_scans_waiting} gauge
 */
@ConfigurationProperties(prefix = "probe.bitrate-scans-waiting")
public record BitrateScansWaitingProperties(Duration refreshInterval) {

  // The OTLP registry's own publish step.
  private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(1);

  public BitrateScansWaitingProperties {
    if (refreshInterval == null) {
      refreshInterval = DEFAULT_REFRESH_INTERVAL;
    }

    if (!refreshInterval.isPositive()) {
      throw new IllegalArgumentException(
          "The waiting bitrate scans refresh interval must be positive");
    }
  }
}
