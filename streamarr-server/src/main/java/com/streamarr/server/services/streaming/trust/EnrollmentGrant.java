package com.streamarr.server.services.streaming.trust;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record EnrollmentGrant(
    UUID grantId,
    UUID workerId,
    PublicTrustBundleRef trustBundle,
    Instant createdAt,
    Instant expiresAt) {

  public EnrollmentGrant {
    Objects.requireNonNull(grantId);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(trustBundle);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("Enrollment grant expiry must follow creation");
    }
  }
}
