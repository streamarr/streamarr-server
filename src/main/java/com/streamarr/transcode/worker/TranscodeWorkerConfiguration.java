package com.streamarr.transcode.worker;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TranscodeWorkerConfiguration(
    UUID workerId,
    UUID bootId,
    int availableSlots,
    PemTlsIdentity tlsIdentity,
    Map<UUID, Path> sourceNamespaces,
    Path segmentBasePath,
    Duration keepAliveTime,
    Duration keepAliveTimeout) {

  private static final Duration DEFAULT_KEEPALIVE_TIME = Duration.ofSeconds(30);
  private static final Duration DEFAULT_KEEPALIVE_TIMEOUT = Duration.ofSeconds(10);

  public TranscodeWorkerConfiguration {
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(bootId);
    if (availableSlots < 1) {
      throw new IllegalArgumentException("Available slots must be positive");
    }
    Objects.requireNonNull(tlsIdentity);
    sourceNamespaces = Map.copyOf(sourceNamespaces);
    Objects.requireNonNull(segmentBasePath);
    if (keepAliveTime == null) {
      keepAliveTime = DEFAULT_KEEPALIVE_TIME;
    }
    if (keepAliveTimeout == null) {
      keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;
    }
  }
}
