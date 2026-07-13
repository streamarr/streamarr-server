package com.streamarr.server.services.streaming.trust;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record EnrollmentGrantRequest(UUID workerId, Sha256Digest tokenSha256, Duration lifetime) {

  private static final Duration DATABASE_PRECISION = Duration.ofNanos(1_000);

  public EnrollmentGrantRequest {
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(tokenSha256);
    Objects.requireNonNull(lifetime);
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("Enrollment grant lifetime must be positive");
    }
    if (lifetime.compareTo(DATABASE_PRECISION) < 0 || lifetime.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Enrollment grant lifetime must use positive whole microseconds");
    }
  }
}
