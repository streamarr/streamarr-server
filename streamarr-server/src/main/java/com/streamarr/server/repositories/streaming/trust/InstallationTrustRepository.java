package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import com.streamarr.server.services.streaming.trust.PublicTrustBundle;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InstallationTrustRepository {

  UUID installationId();

  Instant databaseTime();

  Optional<InstallationTrust> findInitialized();

  Optional<PublicTrustBundle> findBundle(UUID installationId, long version);

  boolean publishInitial(CertificateSigningLease lease, InitialTrustPublication publication);
}
