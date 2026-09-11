package com.streamarr.server.services.streaming.remote;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record WorkerSessionListeners(
    @NonNull Optional<WorkerSessionServerConfiguration> mutualTls,
    @NonNull OptionalInt loopbackPort) {

  public WorkerSessionListeners {
    if (loopbackPort.isPresent()
        && (loopbackPort.getAsInt() < 0 || loopbackPort.getAsInt() > 65535)) {
      throw new IllegalArgumentException(
          "Loopback worker session port must be between 0 and 65535");
    }
  }

  public static class WorkerSessionListenersBuilder {
    private Optional<WorkerSessionServerConfiguration> mutualTls = Optional.empty();
    private OptionalInt loopbackPort = OptionalInt.empty();
  }
}
