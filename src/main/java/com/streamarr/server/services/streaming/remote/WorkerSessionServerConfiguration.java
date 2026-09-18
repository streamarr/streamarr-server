package com.streamarr.server.services.streaming.remote;

import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record WorkerSessionServerConfiguration(
    @NonNull String address, int port, Duration probeTimeout, Duration probeCancellationTimeout) {

  public WorkerSessionServerConfiguration {
    probeTimeout = probeTimeout == null ? Duration.ofMinutes(1) : probeTimeout;
    probeCancellationTimeout =
        probeCancellationTimeout == null ? Duration.ofSeconds(5) : probeCancellationTimeout;

    if (probeTimeout.isZero() || probeTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker probe timeout must be positive");
    }

    if (probeCancellationTimeout.isZero() || probeCancellationTimeout.isNegative()) {
      throw new IllegalArgumentException("Worker probe cancellation timeout must be positive");
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
