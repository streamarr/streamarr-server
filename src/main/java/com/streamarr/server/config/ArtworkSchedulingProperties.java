package com.streamarr.server.config;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param primaryThreads how many targets' primary artwork steps run at once
 * @param backgroundThreads how many targets' background artwork steps run at once
 */
@Builder
@ConfigurationProperties(prefix = "artwork.scheduling")
public record ArtworkSchedulingProperties(Integer primaryThreads, Integer backgroundThreads) {

  private static final int DEFAULT_PRIMARY_THREADS = 16;
  private static final int DEFAULT_BACKGROUND_THREADS = 4;

  public ArtworkSchedulingProperties {
    if (primaryThreads == null) {
      primaryThreads = DEFAULT_PRIMARY_THREADS;
    }

    if (backgroundThreads == null) {
      backgroundThreads = DEFAULT_BACKGROUND_THREADS;
    }

    if (primaryThreads < 1 || backgroundThreads < 1) {
      throw new IllegalArgumentException("Artwork schedulers need at least one thread each");
    }
  }
}
