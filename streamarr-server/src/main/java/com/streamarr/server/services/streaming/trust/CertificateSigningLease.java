package com.streamarr.server.services.streaming.trust;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CertificateSigningLease(
    CertificateAuthorityOperation operation,
    UUID ownerId,
    long fencingEpoch,
    Instant databaseTime,
    Instant leaseUntil) {

  public CertificateSigningLease {
    if (fencingEpoch <= 0) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease epoch must be positive");
    }
    Objects.requireNonNull(operation);
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(databaseTime);
    Objects.requireNonNull(leaseUntil);
    if (!leaseUntil.isAfter(databaseTime)) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease expiry must be later than database time");
    }
  }
}
