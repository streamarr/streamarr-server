package com.streamarr.server.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param threads how many library operations run at once; further operations show their busy status
 *     and wait for a thread
 * @param shutdownDrainTimeout how long a shutdown lets the admitted tasks of running library
 *     operations finish before it interrupts them; the operations stay stored and run again at the
 *     next start
 */
@Builder
@ConfigurationProperties(prefix = "library.operation-scheduling")
public record LibraryOperationSchedulingProperties(Integer threads, Duration shutdownDrainTimeout) {

  private static final int DEFAULT_THREADS = 8;
  private static final Duration DEFAULT_SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(30);

  public LibraryOperationSchedulingProperties {
    if (threads == null) {
      threads = DEFAULT_THREADS;
    }

    if (shutdownDrainTimeout == null) {
      shutdownDrainTimeout = DEFAULT_SHUTDOWN_DRAIN_TIMEOUT;
    }

    if (threads < 1) {
      throw new IllegalArgumentException(
          "The library-operation scheduler needs at least one thread");
    }

    if (!shutdownDrainTimeout.isPositive()) {
      throw new IllegalArgumentException(
          "The library-operation scheduler needs a positive shutdown drain timeout");
    }
  }
}
