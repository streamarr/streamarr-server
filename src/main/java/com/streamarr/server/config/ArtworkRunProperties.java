package com.streamarr.server.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resultCheckInterval how often a scan or refresh that waits for primary artwork checks its
 *     recorded results
 * @param taskResultCheckInterval how often an admitted scan or refresh task that waits for the
 *     primary artwork it requested checks its recorded results; the task holds its admission while
 *     it waits
 */
@Builder
@ConfigurationProperties(prefix = "artwork.runs")
public record ArtworkRunProperties(Duration resultCheckInterval, Duration taskResultCheckInterval) {

  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);
  private static final Duration DEFAULT_TASK_RESULT_CHECK_INTERVAL = Duration.ofMillis(100);

  public ArtworkRunProperties {
    if (resultCheckInterval == null) {
      resultCheckInterval = DEFAULT_RESULT_CHECK_INTERVAL;
    }

    if (taskResultCheckInterval == null) {
      taskResultCheckInterval = DEFAULT_TASK_RESULT_CHECK_INTERVAL;
    }

    if (!resultCheckInterval.isPositive()) {
      throw new IllegalArgumentException("Artwork result check interval must be positive");
    }

    if (!taskResultCheckInterval.isPositive()) {
      throw new IllegalArgumentException("Artwork task result check interval must be positive");
    }
  }
}
