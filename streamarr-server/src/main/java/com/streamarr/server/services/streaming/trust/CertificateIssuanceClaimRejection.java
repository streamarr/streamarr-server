package com.streamarr.server.services.streaming.trust;

public enum CertificateIssuanceClaimRejection {
  SIGNING_LEASE_UNAVAILABLE,
  ENROLLMENT_GRANT_INVALID,
  REQUEST_CONFLICT
}
