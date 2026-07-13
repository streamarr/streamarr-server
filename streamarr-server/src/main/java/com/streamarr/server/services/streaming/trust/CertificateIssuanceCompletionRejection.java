package com.streamarr.server.services.streaming.trust;

public enum CertificateIssuanceCompletionRejection {
  SIGNING_LEASE_UNAVAILABLE,
  CLAIM_UNAVAILABLE,
  CERTIFICATE_MISMATCH
}
