package com.streamarr.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
    int maxConcurrentTranscodes, int segmentDurationSeconds, int sessionTimeoutSeconds) {

  public StreamingProperties {
    if (maxConcurrentTranscodes <= 0) {
      maxConcurrentTranscodes = 8;
    }
    if (segmentDurationSeconds <= 0) {
      segmentDurationSeconds = 6;
    }
    if (sessionTimeoutSeconds <= 0) {
      sessionTimeoutSeconds = 60;
    }
  }
}
