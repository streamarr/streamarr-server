package com.streamarr.server.config;

import java.nio.file.Path;
import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
    Integer maxConcurrentTranscodes,
    Integer maxFormatAttempts,
    Duration targetSegmentDuration,
    Duration sessionTimeout,
    // A producer that publishes nothing for this long is classified stalled and replaced; the
    // recovery budget is attempt-bounded (targets × threshold), never a wall clock.
    Duration producerStallThreshold,
    String segmentBasePath,
    // How long createStreamSession waits for the probe of a file that has no probe outcome yet.
    Duration probeWaitLimit,
    // While a seek's first segment is pending, a job attempt on the seek's worker pauses when the
    // media stored ahead of its viewer outlasts the hold limit and its next segment by more than
    // this margin, which covers errors in the viewer's position (ADR 0044, seek priority).
    Duration seekPriorityPositionMargin,
    // The longest the worker keeps such a job attempt paused without a later yield command.
    Duration seekPriorityHoldLimit,
    // A job attempt whose own media stored ahead of its viewer outlasts this high-water mark is
    // held
    // back until that media falls to the low-water mark, which leaves the viewer that much media
    // while the job attempt resumes (ADR 0044, hold-back).
    Duration holdBackHighWater,
    Duration holdBackLowWater) {

  public StreamingProperties {
    if (maxConcurrentTranscodes == null) {
      maxConcurrentTranscodes = 8;
    }

    if (maxConcurrentTranscodes <= 0) {
      throw new IllegalArgumentException(
          "streaming.max-concurrent-transcodes must be positive, got " + maxConcurrentTranscodes);
    }

    if (maxFormatAttempts == null) {
      maxFormatAttempts = 4;
    }

    if (maxFormatAttempts <= 0) {
      throw new IllegalArgumentException(
          "streaming.max-format-attempts must be positive, got " + maxFormatAttempts);
    }

    if (targetSegmentDuration == null) {
      targetSegmentDuration = Duration.ofSeconds(6);
    }

    // Segment duration divides the relocation gap and scales every seek offset; zero divides by
    // zero on the segment-request path.
    requirePositive("streaming.target-segment-duration", targetSegmentDuration);

    if (sessionTimeout == null) {
      sessionTimeout = Duration.ofSeconds(60);
    }

    if (producerStallThreshold == null) {
      producerStallThreshold = Duration.ofSeconds(10);
    }

    // A zero threshold classifies every producer stalled on its first poll, replacing healthy
    // producers until every execution target is exhausted.
    requirePositive("streaming.producer-stall-threshold", producerStallThreshold);

    if (probeWaitLimit == null) {
      probeWaitLimit = Duration.ofSeconds(15);
    }

    requirePositive("streaming.probe-wait-limit", probeWaitLimit);

    if (seekPriorityPositionMargin == null) {
      seekPriorityPositionMargin = Duration.ofSeconds(3);
    }

    if (seekPriorityPositionMargin.isNegative()) {
      throw new IllegalArgumentException(
          "streaming.seek-priority-position-margin must not be negative, got "
              + seekPriorityPositionMargin);
    }

    if (seekPriorityHoldLimit == null) {
      seekPriorityHoldLimit = Duration.ofSeconds(3);
    }

    requirePositive("streaming.seek-priority-hold-limit", seekPriorityHoldLimit);

    if (holdBackHighWater == null) {
      holdBackHighWater = Duration.ofSeconds(60);
    }

    if (holdBackLowWater == null) {
      holdBackLowWater = Duration.ofSeconds(30);
    }

    requirePositive("streaming.hold-back-low-water", holdBackLowWater);
    if (holdBackHighWater.compareTo(holdBackLowWater) <= 0) {
      throw new IllegalArgumentException(
          "streaming.hold-back-high-water must exceed streaming.hold-back-low-water, got "
              + holdBackHighWater
              + " and "
              + holdBackLowWater);
    }

    if (segmentBasePath == null || segmentBasePath.isBlank()) {
      segmentBasePath =
          Path.of(System.getProperty("java.io.tmpdir"), "streamarr-segments").toString();
    }
  }

  private static void requirePositive(String property, Duration value) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(property + " must be positive, got " + value);
    }
  }
}
