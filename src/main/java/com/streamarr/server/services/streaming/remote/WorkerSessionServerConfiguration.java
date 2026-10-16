package com.streamarr.server.services.streaming.remote;

import static java.util.Objects.requireNonNullElse;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record WorkerSessionServerConfiguration(
    @NonNull String address,
    int port,
    Duration probeTimeout,
    Duration keyframeScanTimeout,
    Duration probeCancellationTimeout,
    Duration startReportTimeout,
    WorkerSessionKeepalive keepalive) {

  public WorkerSessionServerConfiguration {
    probeTimeout = requireNonNullElse(probeTimeout, Duration.ofMinutes(1));
    keyframeScanTimeout = requireNonNullElse(keyframeScanTimeout, Duration.ofMinutes(30));
    probeCancellationTimeout = requireNonNullElse(probeCancellationTimeout, Duration.ofSeconds(5));
    startReportTimeout = requireNonNullElse(startReportTimeout, Duration.ofSeconds(10));
    keepalive = requireNonNullElse(keepalive, WorkerSessionKeepalive.DEFAULT);

    if (probeTimeout.isZero() || probeTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker probe timeout must be positive");
    }

    if (keyframeScanTimeout.isZero() || keyframeScanTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker keyframe scan timeout must be positive");
    }

    if (probeCancellationTimeout.isZero() || probeCancellationTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker probe cancellation timeout must be positive");
    }

    if (startReportTimeout.isZero() || startReportTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker start report timeout must be positive");
    }

    if (address.isBlank()) {
      throw new IllegalArgumentException("Worker session address is required");
    }

    if (port < 0 || port > 65_535) {
      throw new IllegalArgumentException("Worker session port must be between 0 and 65535");
    }
  }

  public static class WorkerSessionServerConfigurationBuilder {
    @SuppressWarnings("java:S1068") // Lombok's generated build() reads this default.
    private String address = "127.0.0.1";
  }
}
