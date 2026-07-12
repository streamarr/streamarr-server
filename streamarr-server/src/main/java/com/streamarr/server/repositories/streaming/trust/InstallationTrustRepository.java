package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InstallationTrustRepository {

  UUID installationId();

  Instant databaseTime();

  Optional<InstallationTrust> findInitialized();

  boolean publishInitial(CertificateSigningLease lease, InitialTrustPublication publication);
}
