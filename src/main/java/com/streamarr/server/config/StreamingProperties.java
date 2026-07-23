package com.streamarr.server.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
    Integer maxConcurrentTranscodes,
    Duration targetSegmentDuration,
    Duration sessionTimeout,
    // Session retention contributes the playback token's pause/slow-playback slack; reject a
    // non-positive value at startup rather than minting unusable tokens.
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration sessionRetention,
    // A producer that publishes nothing for this long is classified stalled and replaced; the
    // recovery budget is attempt-bounded (targets × threshold), never a wall clock.
    Duration producerStallThreshold,
    String segmentBasePath,
    String ffmpegPath,
    String ffprobePath) {

  public StreamingProperties {
    if (maxConcurrentTranscodes == null) {
      maxConcurrentTranscodes = 8;
    }

    if (maxConcurrentTranscodes <= 0) {
      throw new IllegalArgumentException(
          "streaming.max-concurrent-transcodes must be positive, got " + maxConcurrentTranscodes);
    }

    if (targetSegmentDuration == null) {
      targetSegmentDuration = Duration.ofSeconds(6);
    }

    if (sessionTimeout == null) {
      sessionTimeout = Duration.ofSeconds(60);
    }

    if (sessionRetention == null) {
      sessionRetention = Duration.ofHours(24);
    }

    if (producerStallThreshold == null) {
      producerStallThreshold = Duration.ofSeconds(10);
    }

    if (segmentBasePath == null || segmentBasePath.isBlank()) {
      segmentBasePath =
          Path.of(System.getProperty("java.io.tmpdir"), "streamarr-segments").toString();
    }
  }
}
