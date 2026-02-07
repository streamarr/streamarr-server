package com.streamarr.server.config;

import java.nio.file.Path;
import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Builder
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
    int maxConcurrentTranscodes,
    Duration segmentDuration,
    Duration sessionTimeout,
    Duration sessionRetention,
    String segmentBasePath,
    String ffmpegPath,
    String ffprobePath) {

  public StreamingProperties {
    if (maxConcurrentTranscodes <= 0) {
      maxConcurrentTranscodes = 8;
    }

    if (segmentDuration == null) {
      segmentDuration = Duration.ofSeconds(6);
    }

    if (sessionTimeout == null) {
      sessionTimeout = Duration.ofSeconds(60);
    }

    if (sessionRetention == null) {
      sessionRetention = Duration.ofHours(24);
    }

    if (segmentBasePath == null || segmentBasePath.isBlank()) {
      segmentBasePath =
          Path.of(System.getProperty("java.io.tmpdir"), "streamarr-segments").toString();
    }
  }
}
