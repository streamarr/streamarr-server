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
    int maxConcurrentTranscodes,
    Duration segmentDuration,
    Duration sessionTimeout,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration provisioningTimeout,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration transcodeStartupTimeout,
    // Session retention contributes the playback token's pause/slow-playback slack; reject a
    // non-positive value at startup rather than minting unusable tokens.
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration sessionRetention,
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

    if (provisioningTimeout == null) {
      provisioningTimeout = Duration.ofMinutes(2);
    }

    if (transcodeStartupTimeout == null) {
      transcodeStartupTimeout = Duration.ofSeconds(30);
    }
    if (transcodeStartupTimeout.isZero()
        || transcodeStartupTimeout.isNegative()
        || transcodeStartupTimeout.compareTo(provisioningTimeout) >= 0) {
      throw new IllegalArgumentException(
          "Transcode startup timeout must be positive and shorter than provisioning timeout");
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
