package com.streamarr.server.services.streaming.trust;

import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

public sealed interface CertificateIssuanceClaimResult {

  @Builder
  record ReadyToSign(
      UUID requestId,
      UUID workerId,
      PublicTrustBundleRef trustBundle,
      SubjectPublicKeyInfo subjectPublicKeyInfo,
      CertificateIssuanceParameters parameters,
      long signingFencingEpoch)
      implements CertificateIssuanceClaimResult {

    public ReadyToSign {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(workerId);
      Objects.requireNonNull(trustBundle);
      Objects.requireNonNull(subjectPublicKeyInfo);
      Objects.requireNonNull(parameters);
      if (signingFencingEpoch <= 0) {
        throw new IllegalArgumentException("Certificate issuance signing epoch must be positive");
      }
    }
  }

  record Rejected(CertificateIssuanceClaimRejection reason)
      implements CertificateIssuanceClaimResult {

    public Rejected {
      Objects.requireNonNull(reason);
    }
  }

  record Completed(IssuedWorkerCertificate certificate) implements CertificateIssuanceClaimResult {

    public Completed {
      Objects.requireNonNull(certificate);
    }
  }

  record RetryWithNewParameters() implements CertificateIssuanceClaimResult {}
}
