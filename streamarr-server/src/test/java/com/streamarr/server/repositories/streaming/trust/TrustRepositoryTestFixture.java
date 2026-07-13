package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;
import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeInstallation.TRANSCODE_INSTALLATION;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerCertificateIssuance.TRANSCODE_WORKER_CERTIFICATE_ISSUANCE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerIdentity.TRANSCODE_WORKER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import org.jooq.DSLContext;

@Builder
final class TrustRepositoryTestFixture {

  private final DSLContext dsl;
  private final InstallationTrustRepository trustRepository;
  private final CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  private final Duration signingLeaseDuration;

  void reset() {
    dsl.deleteFrom(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE).execute();
    dsl.deleteFrom(TRANSCODE_ENROLLMENT_GRANT).execute();
    dsl.deleteFrom(TRANSCODE_WORKER_IDENTITY).execute();
    dsl.update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, (Long) null)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT, (OffsetDateTime) null)
        .execute();
    dsl.deleteFrom(TRANSCODE_TRUST_CERTIFICATE).execute();
    dsl.deleteFrom(TRANSCODE_PUBLIC_TRUST_BUNDLE).execute();
    dsl.update(TRANSCODE_INSTALLATION)
        .set(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256, (byte[]) null)
        .set(TRANSCODE_INSTALLATION.INITIALIZED_AT, (OffsetDateTime) null)
        .execute();
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.OPERATION, (TranscodeCaSigningOperation) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID, (UUID) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, (OffsetDateTime) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH, 0L)
        .execute();
  }

  InstallationTrust bootstrap() {
    return bootstrap(false);
  }

  InstallationTrust bootstrapAndRelease() {
    return bootstrap(true);
  }

  private InstallationTrust bootstrap(boolean releaseLease) {
    var installationId = trustRepository.installationId();
    var material =
        new BuiltInCertificateAuthority().create(installationId, trustRepository.databaseTime());
    var lease =
        signingLeaseRepository
            .tryAcquire(
                CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), signingLeaseDuration)
            .orElseThrow();
    assertThat(trustRepository.publishInitial(lease, InitialTrustPublication.from(material)))
        .isTrue();
    if (releaseLease) {
      assertThat(signingLeaseRepository.release(lease)).isTrue();
    }
    return trustRepository.findInitialized().orElseThrow();
  }
}
