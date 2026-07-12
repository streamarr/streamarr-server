package com.streamarr.transcode.engine.model;

import java.time.Duration;
import lombok.Builder;

@Builder
public record TranscodeExecutionParameters(
    int seekPosition,
    int segmentDuration,
    double framerate,
    int startNumber,
    Duration startupTimeout) {

  public TranscodeExecutionParameters {
    if (seekPosition < 0 || startNumber < 0) {
      throw new IllegalArgumentException("Seek position and start number cannot be negative");
    }
    if (segmentDuration < 1) {
      throw new IllegalArgumentException("Segment duration must be positive");
    }
    if (!Double.isFinite(framerate) || framerate <= 0) {
      throw new IllegalArgumentException("Framerate must be finite and positive");
    }
    if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
      throw new IllegalArgumentException("Startup timeout must be positive");
    }
    try {
      startupTimeout.toNanos();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          "Startup timeout must fit nanosecond precision", exception);
    }
  }
}
