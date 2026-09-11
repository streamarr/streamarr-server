package com.streamarr.transcode.worker;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeWorkerConfiguration(
    @NonNull UUID workerId,
    @NonNull UUID bootId,
    int availableSlots,
    int healthPort,
    boolean plaintext,
    @NonNull Optional<PemTlsIdentity> tlsIdentity,
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

    if (!plaintext && tlsIdentity.isEmpty()) {
      throw new IllegalArgumentException("Mutual TLS worker identity is required");
    }

    if (plaintext && tlsIdentity.isPresent()) {
      throw new IllegalArgumentException("Plaintext workers must not configure a TLS identity");
    }

    if (healthPort < 0 || healthPort > 65_535) {
      throw new IllegalArgumentException("Worker health port must be between 0 and 65535");
    }

    sourceNamespaces = Map.copyOf(sourceNamespaces);
    if (keepAliveTime == null) {
      keepAliveTime = DEFAULT_KEEPALIVE_TIME;
    }
    if (keepAliveTimeout == null) {
      keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;
    }
  }

  public static class TranscodeWorkerConfigurationBuilder {
    private Optional<PemTlsIdentity> tlsIdentity = Optional.empty();

    public TranscodeWorkerConfigurationBuilder tlsIdentity(@NonNull PemTlsIdentity identity) {
      return tlsIdentity(Optional.of(identity));
    }

    public TranscodeWorkerConfigurationBuilder tlsIdentity(
        @NonNull Optional<PemTlsIdentity> identity) {
      tlsIdentity = identity;
      return this;
    }
  }
}
