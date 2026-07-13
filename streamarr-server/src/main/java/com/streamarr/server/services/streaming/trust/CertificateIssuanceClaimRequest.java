package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import lombok.Builder;

@Builder
public record CertificateIssuanceClaimRequest(
    EnrollmentCertificateRequest request, CertificateIssuanceParameters proposedParameters) {

  public CertificateIssuanceClaimRequest {
    Objects.requireNonNull(request);
    Objects.requireNonNull(proposedParameters);
  }
}
