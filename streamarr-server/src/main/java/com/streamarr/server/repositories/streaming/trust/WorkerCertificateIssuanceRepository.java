package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletion;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletionResult;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.EnrollmentCertificateRequest;
import com.streamarr.server.services.streaming.trust.IssuedWorkerCertificate;
import java.util.Optional;

public interface WorkerCertificateIssuanceRepository {

  CertificateIssuanceClaimResult claim(
      CertificateIssuanceClaimRequest request, CertificateSigningLease lease);

  CertificateIssuanceCompletionResult complete(
      CertificateIssuanceCompletion completion, CertificateSigningLease lease);

  Optional<IssuedWorkerCertificate> findCompleted(EnrollmentCertificateRequest request);
}
