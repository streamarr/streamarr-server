package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;
import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeInstallation.TRANSCODE_INSTALLATION;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerIdentity.TRANSCODE_WORKER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation;
import com.streamarr.server.jooq.generated.enums.TranscodeTrustCertificateKind;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Installation Trust Repository Integration Tests")
class InstallationTrustRepositoryIT extends AbstractIntegrationTest {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  @Autowired private InstallationTrustRepository repository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void resetTrustState() {
    dsl.deleteFrom(TRANSCODE_ENROLLMENT_GRANT).execute();
    dsl.deleteFrom(TRANSCODE_WORKER_IDENTITY).execute();
    dsl.update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, (Long) null)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT, (OffsetDateTime) null)
        .execute();
    dsl.update(TRANSCODE_INSTALLATION)
        .set(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256, (byte[]) null)
        .set(TRANSCODE_INSTALLATION.INITIALIZED_AT, (OffsetDateTime) null)
        .execute();
    dsl.deleteFrom(TRANSCODE_TRUST_CERTIFICATE).execute();
    dsl.deleteFrom(TRANSCODE_PUBLIC_TRUST_BUNDLE).execute();
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.OPERATION, (TranscodeCaSigningOperation) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID, (UUID) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, (OffsetDateTime) null)
        .set(TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH, 0L)
        .execute();
  }

  @Test
  @DisplayName("Should refuse initial publication under a non-bootstrap signing lease")
  void shouldRefuseInitialPublicationUnderNonBootstrapSigningLease() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();

    var published = repository.publishInitial(lease, publication);

    assertThat(published).isFalse();
    assertThat(repository.findInitialized()).isEmpty();
    assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isZero();
    assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isZero();
  }

  @Test
  @DisplayName("Should reject stored certificate when digest does not match exact DER")
  void shouldRejectStoredCertificateWhenDigestDoesNotMatchExactDer() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    dsl.update(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256, new byte[32])
        .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.TRUST_ANCHOR))
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("digest");
  }

  @Test
  @DisplayName("Should reject stored certificate when bytes are not canonical DER")
  void shouldRejectStoredCertificateWhenBytesAreNotCanonicalDer() throws Exception {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    var certificateDer =
        dsl.select(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER)
            .from(TRANSCODE_TRUST_CERTIFICATE)
            .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.ISSUER))
            .fetchSingle(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER);
    var nonCanonicalDer = Arrays.copyOf(certificateDer, certificateDer.length + 1);
    dsl.update(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, nonCanonicalDer)
        .set(
            TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256,
            MessageDigest.getInstance("SHA-256").digest(nonCanonicalDer))
        .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.ISSUER))
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("canonical DER");
  }

  @Test
  @DisplayName("Should reject initial bundle when a certificate has a nonzero ordinal")
  void shouldRejectInitialBundleWhenCertificateHasNonzeroOrdinal() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    dsl.update(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.ORDINAL, (short) 1)
        .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.ISSUER))
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("role and ordinal");
  }

  @Test
  @DisplayName("Should reject initial bundle when a required certificate role is missing")
  void shouldRejectInitialBundleWhenRequiredCertificateRoleIsMissing() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    dsl.deleteFrom(TRANSCODE_TRUST_CERTIFICATE)
        .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.REVOCATION_SIGNER))
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("exact certificate roles");
  }

  @Test
  @DisplayName("Should reject partially initialized trust state through typed boundary")
  void shouldRejectPartiallyInitializedTrustStateThroughTypedBoundary() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    dsl.update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, (Long) null)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT, (OffsetDateTime) null)
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("partially initialized");
  }

  @Test
  @DisplayName("Should reject initialized state when root fingerprint does not match trust anchor")
  void shouldRejectInitializedStateWhenRootFingerprintDoesNotMatchTrustAnchor() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    assertThat(repository.publishInitial(lease, publication())).isTrue();
    dsl.update(TRANSCODE_INSTALLATION)
        .set(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256, new byte[32])
        .execute();

    assertThatThrownBy(repository::findInitialized)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("root fingerprint");
  }

  @Test
  @DisplayName("Should roll back every trust row when final publication write loses")
  void shouldRollBackEveryTrustRowWhenFinalPublicationWriteLoses() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    installSuppressedActivationTrigger();
    var publication = publication();

    try {
      assertThatThrownBy(() -> repository.publishInitial(lease, publication))
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("publication");
      assertThat(repository.findInitialized()).isEmpty();
      assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isZero();
      assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isZero();
      assertThat(
              dsl.select(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256)
                  .from(TRANSCODE_INSTALLATION)
                  .fetchSingle(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256))
          .isNull();
      assertThat(
              dsl.select(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION)
                  .from(TRANSCODE_ACTIVE_TRUST_BUNDLE)
                  .fetchSingle(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION))
          .isNull();
    } finally {
      removeSuppressedActivationTrigger();
    }
  }

  @Test
  @DisplayName("Should refuse publication when lease expires while waiting for its row lock")
  void shouldRefusePublicationWhenLeaseExpiresWhileWaitingForItsRowLock() throws Exception {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();
    var expiresAt = repository.databaseTime().plusSeconds(2);
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, expiresAt.atOffset(ZoneOffset.UTC))
        .execute();

    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        DSL.using(configuration)
                            .selectOne()
                            .from(TRANSCODE_CA_SIGNING_LEASE)
                            .where(TRANSCODE_CA_SIGNING_LEASE.SINGLETON.isTrue())
                            .forUpdate()
                            .fetchSingle();
                        rowLocked.countDown();
                        releaseRow.await();
                      }));
      assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

      var publisher = executor.submit(() -> repository.publishInitial(lease, publication));
      try {
        awaitTableReadBlocked("transcode_ca_signing_lease", "Signing lease");
        awaitDatabaseTime(expiresAt);
      } finally {
        releaseRow.countDown();
      }

      assertThat(publisher.get(5, TimeUnit.SECONDS)).isFalse();
      blocker.get(5, TimeUnit.SECONDS);
    }
    assertThat(repository.findInitialized()).isEmpty();
    assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isZero();
    assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isZero();
  }

  @Test
  @DisplayName("Should roll back publication when lease expires during final write")
  void shouldRollBackPublicationWhenLeaseExpiresDuringFinalWrite() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();
    dsl.update(TRANSCODE_CA_SIGNING_LEASE)
        .set(
            TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL,
            repository.databaseTime().plusSeconds(2).atOffset(ZoneOffset.UTC))
        .execute();
    installDelayedActivationTrigger();

    try {
      assertThatThrownBy(() -> repository.publishInitial(lease, publication))
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("lease expired");
      assertThat(repository.findInitialized()).isEmpty();
      assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isZero();
      assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isZero();
    } finally {
      removeDelayedActivationTrigger();
    }
  }

  @Test
  @DisplayName("Should read one coherent trust snapshot while active bundle is replaced")
  void shouldReadOneCoherentTrustSnapshotWhileActiveBundleIsReplaced() throws Exception {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();
    assertThat(repository.publishInitial(lease, publication)).isTrue();

    var tableLocked = new CountDownLatch(1);
    var replaceBundle = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replacement =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        var transaction = DSL.using(configuration);
                        transaction.execute(
                            "LOCK TABLE transcode_trust_certificate IN ACCESS EXCLUSIVE MODE");
                        tableLocked.countDown();
                        replaceBundle.await();
                        replaceActiveBundle(transaction, publication);
                      }));
      assertThat(tableLocked.await(5, TimeUnit.SECONDS)).isTrue();

      var reader = executor.submit(repository::findInitialized);
      try {
        awaitCertificateReadBlocked();
      } finally {
        replaceBundle.countDown();
      }

      var observed = reader.get(5, TimeUnit.SECONDS).orElseThrow();
      replacement.get(5, TimeUnit.SECONDS);
      assertThat(observed.activeBundle().version()).isIn(1L, 2L);
      assertThat(observed.activeBundle().trustAnchors()).hasSize(1);
      assertThat(observed.activeBundle().issuers()).hasSize(1);
      assertThat(observed.activeBundle().revocationSigners()).hasSize(1);
    }
  }

  @Test
  @DisplayName("Should load exact historical public trust bundle after active bundle advances")
  void shouldLoadExactHistoricalPublicTrustBundleAfterActiveBundleAdvances() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();
    assertThat(repository.publishInitial(lease, publication)).isTrue();
    var historicalBundle = repository.findInitialized().orElseThrow().activeBundle();
    dsl.transaction(configuration -> activateSecondBundle(DSL.using(configuration), publication));

    assertThat(repository.findInitialized().orElseThrow().activeBundle().version()).isEqualTo(2L);
    assertThat(repository.findBundle(publication.installationId(), 1L)).contains(historicalBundle);
  }

  @Test
  @DisplayName("Should load overlapping certificate roles from later public trust bundle")
  void shouldLoadOverlappingCertificateRolesFromLaterPublicTrustBundle() {
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), LEASE_DURATION)
            .orElseThrow();
    var publication = publication();
    assertThat(repository.publishInitial(lease, publication)).isTrue();
    var overlap =
        InitialTrustPublication.from(
            new BuiltInCertificateAuthority()
                .create(
                    publication.installationId(),
                    repository.databaseTime().plus(Duration.ofMinutes(1))));
    dsl.transaction(
        configuration -> {
          var transaction = DSL.using(configuration);
          activateSecondBundle(transaction, publication);
          insertCertificate(
              transaction, overlap, TranscodeTrustCertificateKind.TRUST_ANCHOR, (short) 1);
          insertCertificate(transaction, overlap, TranscodeTrustCertificateKind.ISSUER, (short) 1);
          insertCertificate(
              transaction, overlap, TranscodeTrustCertificateKind.REVOCATION_SIGNER, (short) 1);
        });

    var bundle = repository.findBundle(publication.installationId(), 2L).orElseThrow();

    assertThat(bundle.trustAnchors()).hasSize(2);
    assertThat(bundle.issuers()).hasSize(2);
    assertThat(bundle.revocationSigners()).hasSize(2);
  }

  private InitialTrustPublication publication() {
    var material =
        new BuiltInCertificateAuthority()
            .create(repository.installationId(), repository.databaseTime());
    return InitialTrustPublication.from(material);
  }

  private void awaitDatabaseTime(Instant expected) {
    while (repository.databaseTime().isBefore(expected)) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
  }

  private void awaitCertificateReadBlocked() {
    awaitTableReadBlocked("transcode_trust_certificate", "Trust certificate");
  }

  private void awaitTableReadBlocked(String tableName, String description) {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      var blocked =
          dsl.fetchExists(
              DSL.selectOne()
                  .from(DSL.table(DSL.name("pg_catalog", "pg_stat_activity")))
                  .where(DSL.field(DSL.name("wait_event_type"), String.class).eq("Lock"))
                  .and(DSL.field(DSL.name("query"), String.class).contains(tableName)));
      if (blocked) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    throw new AssertionError(description + " read did not block on the database lock");
  }

  private void replaceActiveBundle(DSLContext transaction, InitialTrustPublication publication) {
    activateSecondBundle(transaction, publication);
    transaction
        .deleteFrom(TRANSCODE_TRUST_CERTIFICATE)
        .where(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION.eq(1L))
        .execute();
    transaction
        .deleteFrom(TRANSCODE_PUBLIC_TRUST_BUNDLE)
        .where(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION.eq(1L))
        .execute();
  }

  private void activateSecondBundle(DSLContext transaction, InitialTrustPublication publication) {
    transaction
        .insertInto(TRANSCODE_PUBLIC_TRUST_BUNDLE)
        .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID, publication.installationId())
        .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION, 2L)
        .execute();
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.TRUST_ANCHOR);
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.ISSUER);
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.REVOCATION_SIGNER);
    transaction
        .update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, 2L)
        .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT, DSL.currentOffsetDateTime())
        .execute();
  }

  private void insertCertificate(
      DSLContext transaction,
      InitialTrustPublication publication,
      TranscodeTrustCertificateKind kind) {
    insertCertificate(transaction, publication, kind, (short) 0);
  }

  private void insertCertificate(
      DSLContext transaction,
      InitialTrustPublication publication,
      TranscodeTrustCertificateKind kind,
      short ordinal) {
    var certificateDer =
        switch (kind) {
          case TRUST_ANCHOR -> publication.rootCertificateDer();
          case ISSUER -> publication.issuerCertificateDer();
          case REVOCATION_SIGNER -> publication.revocationSignerCertificateDer();
        };
    transaction
        .insertInto(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID, publication.installationId())
        .set(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION, 2L)
        .set(TRANSCODE_TRUST_CERTIFICATE.KIND, kind)
        .set(TRANSCODE_TRUST_CERTIFICATE.ORDINAL, ordinal)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, certificateDer)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256, sha256(certificateDer))
        .execute();
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void installSuppressedActivationTrigger() {
    dsl.execute(
        """
        CREATE FUNCTION suppress_initial_trust_activation() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          RETURN NULL;
        END
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER suppress_initial_trust_activation
        BEFORE UPDATE ON transcode_active_trust_bundle
        FOR EACH ROW
        WHEN (NEW.bundle_version IS NOT NULL)
        EXECUTE FUNCTION suppress_initial_trust_activation()
        """);
  }

  private void removeSuppressedActivationTrigger() {
    dsl.execute(
        "DROP TRIGGER IF EXISTS suppress_initial_trust_activation "
            + "ON transcode_active_trust_bundle");
    dsl.execute("DROP FUNCTION IF EXISTS suppress_initial_trust_activation()");
  }

  private void installDelayedActivationTrigger() {
    dsl.execute(
        """
        CREATE FUNCTION delay_initial_trust_activation() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          PERFORM pg_sleep(3);
          RETURN NEW;
        END
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER delay_initial_trust_activation
        BEFORE UPDATE ON transcode_active_trust_bundle
        FOR EACH ROW
        WHEN (NEW.bundle_version IS NOT NULL)
        EXECUTE FUNCTION delay_initial_trust_activation()
        """);
  }

  private void removeDelayedActivationTrigger() {
    dsl.execute(
        "DROP TRIGGER IF EXISTS delay_initial_trust_activation "
            + "ON transcode_active_trust_bundle");
    dsl.execute("DROP FUNCTION IF EXISTS delay_initial_trust_activation()");
  }
}
