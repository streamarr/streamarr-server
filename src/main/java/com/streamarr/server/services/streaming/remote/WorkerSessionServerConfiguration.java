package com.streamarr.server.services.streaming.remote;

import lombok.Builder;
import lombok.NonNull;

@Builder
public record WorkerSessionServerConfiguration(@NonNull String address, int port) {

  public WorkerSessionServerConfiguration {
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
