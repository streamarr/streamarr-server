package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import lombok.Builder;

@Builder
public record CertificateIssuanceParameters(
    Sha256Digest issuerCertificateSha256,
    CertificateSerialNumber serialNumber,
    WorkerCertificateProfile profile,
    CertificateValidity validity) {

  public CertificateIssuanceParameters {
    Objects.requireNonNull(issuerCertificateSha256);
    Objects.requireNonNull(serialNumber);
    Objects.requireNonNull(profile);
    Objects.requireNonNull(validity);
  }
}
