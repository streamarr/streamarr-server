package com.streamarr.server.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param primaryThreads how many items' primary artwork steps run at once
 * @param backgroundThreads how many items' background artwork steps run at once
 * @param resultCheckInterval how often a scan or refresh that waits for primary artwork checks its
 *     recorded results
 * @param lockTimeout how long a write of an artwork request or of an attempt's artwork results
 *     waits for the item's request lock and request row before it fails
 */
@Builder
@ConfigurationProperties(prefix = "artwork")
public record ArtworkProperties(
    Integer primaryThreads,
    Integer backgroundThreads,
    Duration resultCheckInterval,
    Duration lockTimeout) {

  private static final int DEFAULT_PRIMARY_THREADS = 16;
  private static final int DEFAULT_BACKGROUND_THREADS = 4;
  private static final Duration DEFAULT_RESULT_CHECK_INTERVAL = Duration.ofSeconds(2);
  private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5);

  public ArtworkProperties {
    if (primaryThreads == null) {
      primaryThreads = DEFAULT_PRIMARY_THREADS;
    }

    if (backgroundThreads == null) {
      backgroundThreads = DEFAULT_BACKGROUND_THREADS;
    }

    if (resultCheckInterval == null) {
      resultCheckInterval = DEFAULT_RESULT_CHECK_INTERVAL;
    }

    if (lockTimeout == null) {
      lockTimeout = DEFAULT_LOCK_TIMEOUT;
    }

    if (primaryThreads < 1 || backgroundThreads < 1) {
      throw new IllegalArgumentException("Artwork schedulers need at least one thread each");
    }

    if (!resultCheckInterval.isPositive()) {
      throw new IllegalArgumentException("Artwork result check interval must be positive");
    }

    if (lockTimeout.compareTo(Duration.ofMillis(1)) < 0) {
      throw new IllegalArgumentException("Artwork lock timeout must be at least 1ms");
    }
  }
}
