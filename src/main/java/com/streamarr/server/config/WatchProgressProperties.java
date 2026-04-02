package com.streamarr.server.config;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Builder
@ConfigurationProperties(prefix = "streaming.watch-progress")
public record WatchProgressProperties(
    double minResumePercent, double maxResumePercent, int maxRemainingSeconds) {

  public WatchProgressProperties {
    if (minResumePercent <= 0) {
      minResumePercent = 5.0;
    }

    if (maxResumePercent <= 0) {
      maxResumePercent = 90.0;
    }

    if (maxRemainingSeconds <= 0) {
      maxRemainingSeconds = 300;
    }
  }
}
