package com.streamarr.server.config;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param threads how many library operations run at once; further operations show their busy status
 *     and wait for a thread
 */
@Builder
@ConfigurationProperties(prefix = "library.operation-scheduling")
public record LibraryOperationSchedulingProperties(Integer threads) {

  private static final int DEFAULT_THREADS = 8;

  public LibraryOperationSchedulingProperties {
    if (threads == null) {
      threads = DEFAULT_THREADS;
    }

    if (threads < 1) {
      throw new IllegalArgumentException(
          "The library-operation scheduler needs at least one thread");
    }
  }
}
