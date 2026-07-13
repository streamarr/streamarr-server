package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record EnrollmentCertificateRequest(
    UUID requestId,
    UUID workerId,
    Sha256Digest tokenSha256,
    SubjectPublicKeyInfo subjectPublicKeyInfo) {

  public EnrollmentCertificateRequest {
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(tokenSha256);
    Objects.requireNonNull(subjectPublicKeyInfo);
  }
}
