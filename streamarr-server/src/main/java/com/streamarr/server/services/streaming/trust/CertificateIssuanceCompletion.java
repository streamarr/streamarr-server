package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CertificateIssuanceCompletion(
    UUID requestId, long signingFencingEpoch, EncodedWorkerCertificate certificate) {

  public CertificateIssuanceCompletion {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(certificate);
    if (signingFencingEpoch <= 0) {
      throw new IllegalArgumentException("Certificate completion signing epoch must be positive");
    }
  }
}
