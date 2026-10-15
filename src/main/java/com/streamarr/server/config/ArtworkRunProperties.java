package com.streamarr.server.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resultCheckInterval how often a scan or refresh that waits for primary artwork checks its
 *     recorded results
 */
@Builder
@ConfigurationProperties(prefix = "artwork.runs")
public record ArtworkRunProperties(Duration resultCheckInterval) {

  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);

  public ArtworkRunProperties {
    if (resultCheckInterval == null) {
      resultCheckInterval = DEFAULT_RESULT_CHECK_INTERVAL;
    }

    if (!resultCheckInterval.isPositive()) {
      throw new IllegalArgumentException("Artwork result check interval must be positive");
    }
  }
}
