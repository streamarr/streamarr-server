package com.streamarr.server.config;

import java.nio.file.Path;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Builder
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
    int maxConcurrentTranscodes,
    int segmentDurationSeconds,
    int sessionTimeoutSeconds,
    String segmentBasePath,
    String ffmpegPath,
    String ffprobePath) {

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
    if (segmentBasePath == null || segmentBasePath.isBlank()) {
      segmentBasePath =
          Path.of(System.getProperty("java.io.tmpdir"), "streamarr-segments").toString();
    }
  }
}
