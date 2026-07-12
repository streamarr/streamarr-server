package com.streamarr.server.services.streaming.trust;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PublicTrustBundle(
    UUID installationId,
    long version,
    Instant createdAt,
    List<X509Certificate> trustAnchors,
    List<X509Certificate> issuers,
    List<X509Certificate> revocationSigners) {

  public PublicTrustBundle {
    if (version <= 0) {
      throw new IllegalArgumentException("Public trust bundle version must be positive");
    }
    Objects.requireNonNull(installationId);
    Objects.requireNonNull(createdAt);
    trustAnchors = List.copyOf(trustAnchors);
    issuers = List.copyOf(issuers);
    revocationSigners = List.copyOf(revocationSigners);
  }
}
