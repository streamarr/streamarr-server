package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import lombok.Builder;
import lombok.NonNull;

/**
 * What the frame-rate rule's packet scan measured over the first 30 s of the chosen video stream.
 * The motion rate is empty when the scan found fewer than 2 frames.
 */
@Builder
public record FrameRatePacketScan(
    @NonNull OptionalDouble motionRate,
    int scannedFrameCount,
    @NonNull Optional<Duration> scannedDuration) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class FrameRatePacketScanBuilder {
    private OptionalDouble motionRate = OptionalDouble.empty();
    private Optional<Duration> scannedDuration = Optional.empty();
  }
}
