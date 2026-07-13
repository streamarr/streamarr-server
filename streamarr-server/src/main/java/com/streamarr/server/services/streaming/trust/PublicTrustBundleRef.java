package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import java.util.UUID;

public record PublicTrustBundleRef(UUID installationId, long version) {

  public PublicTrustBundleRef {
    Objects.requireNonNull(installationId);
    if (version <= 0) {
      throw new IllegalArgumentException("Public trust bundle version must be positive");
    }
  }
}
