package com.streamarr.server.services.streaming.trust;

import java.util.Objects;

public sealed interface CertificateIssuanceCompletionResult {

  record Stored(IssuedWorkerCertificate certificate)
      implements CertificateIssuanceCompletionResult {

    public Stored {
      Objects.requireNonNull(certificate);
    }
  }

  record Rejected(CertificateIssuanceCompletionRejection reason)
      implements CertificateIssuanceCompletionResult {

    public Rejected {
      Objects.requireNonNull(reason);
    }
  }
}
