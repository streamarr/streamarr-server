package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record IssuedWorkerCertificate(
    UUID requestId,
    UUID workerId,
    EncodedWorkerCertificate certificate,
    PublicTrustBundle trustBundle) {

  public IssuedWorkerCertificate {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(trustBundle);
  }
}
