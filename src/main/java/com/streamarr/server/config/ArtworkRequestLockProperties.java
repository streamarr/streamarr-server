package com.streamarr.server.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeout how long a write of an artwork request or of an attempt's artwork results waits
 *     for the target's request lock and request row before it fails
 */
@Builder
@ConfigurationProperties(prefix = "artwork.request-lock")
public record ArtworkRequestLockProperties(Duration timeout) {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  public ArtworkRequestLockProperties {
    if (timeout == null) {
      timeout = DEFAULT_TIMEOUT;
    }

    if (timeout.compareTo(Duration.ofMillis(1)) < 0) {
      throw new IllegalArgumentException("Artwork request lock timeout must be at least 1ms");
    }
  }
}
