package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface CertificateAuthoritySigningLeaseRepository {

  Optional<CertificateSigningLease> tryAcquire(
      CertificateAuthorityOperation operation, UUID ownerId, Duration duration);

  Optional<CertificateSigningLease> renew(CertificateSigningLease currentLease, Duration duration);

  boolean isCurrent(CertificateSigningLease lease);

  boolean release(CertificateSigningLease lease);
}
