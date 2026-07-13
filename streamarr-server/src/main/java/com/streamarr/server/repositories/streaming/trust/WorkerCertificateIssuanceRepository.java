package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;

public interface WorkerCertificateIssuanceRepository {

  CertificateIssuanceClaimResult claim(
      CertificateIssuanceClaimRequest request, CertificateSigningLease lease);
}
