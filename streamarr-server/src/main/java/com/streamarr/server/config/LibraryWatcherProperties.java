package com.streamarr.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "library.watcher")
public record LibraryWatcherProperties(
    int stabilizationPeriodSeconds, int pollIntervalSeconds, int maxWaitSeconds) {

  public LibraryWatcherProperties {
    if (stabilizationPeriodSeconds <= 0) {
      stabilizationPeriodSeconds = 30;
    }

    if (pollIntervalSeconds <= 0) {
      pollIntervalSeconds = 5;
    }

    if (maxWaitSeconds <= 0) {
      maxWaitSeconds = 3600;
    }
  }
}
