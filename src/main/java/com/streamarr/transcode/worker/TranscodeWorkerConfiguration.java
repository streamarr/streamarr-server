package com.streamarr.transcode.worker;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeWorkerConfiguration(
    @NonNull UUID workerId,
    @NonNull UUID bootId,
    int availableSlots,
    @NonNull PemTlsIdentity tlsIdentity,
    @NonNull Map<UUID, Path> sourceNamespaces,
    @NonNull Path segmentBasePath,
    Duration keepAliveTime,
    Duration keepAliveTimeout) {

  private static final Duration DEFAULT_KEEPALIVE_TIME = Duration.ofSeconds(30);
  private static final Duration DEFAULT_KEEPALIVE_TIMEOUT = Duration.ofSeconds(10);

  public TranscodeWorkerConfiguration {
    if (availableSlots < 1) {
      throw new IllegalArgumentException("Available slots must be positive");
    }
    sourceNamespaces = Map.copyOf(sourceNamespaces);
    if (keepAliveTime == null) {
      keepAliveTime = DEFAULT_KEEPALIVE_TIME;
    }
    if (keepAliveTimeout == null) {
      keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;
    }
  }
}
