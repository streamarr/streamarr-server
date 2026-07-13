package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.util.Objects;
import lombok.Builder;

@Builder
public record WorkerSessionServerConfiguration(
    int port, String trustDomain, PemTlsIdentity tlsIdentity) {

  public WorkerSessionServerConfiguration {
    if (port < 0 || port > 65_535) {
      throw new IllegalArgumentException("Worker session port must be between 0 and 65535");
    }
    if (trustDomain == null || trustDomain.isBlank()) {
      throw new IllegalArgumentException("Worker trust domain is required");
    }
    Objects.requireNonNull(tlsIdentity);
  }
}
