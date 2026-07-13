package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerIdentity.TRANSCODE_WORKER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.TranscodeTrustCertificateKind;
import com.streamarr.server.services.streaming.trust.EnrollmentGrantRequest;
import com.streamarr.server.services.streaming.trust.GrantCreationConflict;
import com.streamarr.server.services.streaming.trust.GrantCreationResult;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Worker Enrollment Repository Integration Tests")
class WorkerEnrollmentRepositoryIT extends AbstractIntegrationTest {

  private static final Duration SIGNING_LEASE = Duration.ofSeconds(30);

  @Autowired private InstallationTrustRepository trustRepository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private WorkerEnrollmentRepository repository;
  @Autowired private DSLContext dsl;
  private TrustRepositoryTestFixture trustFixture;

  @BeforeEach
  void resetTrustState() {
    trustFixture =
        TrustRepositoryTestFixture.builder()
            .dsl(dsl)
            .trustRepository(trustRepository)
            .signingLeaseRepository(signingLeaseRepository)
            .signingLeaseDuration(SIGNING_LEASE)
            .build();
    trustFixture.reset();
  }

  @Test
  @DisplayName("Should create enrollment grant pinned to exact active public trust bundle")
  void shouldCreateEnrollmentGrantPinnedToExactActivePublicTrustBundle() {
    var trust = bootstrapTrust();
    var workerId = UUID.randomUUID();
    var tokenDigest = new Sha256Digest(new byte[32]);
    var lifetime = Duration.ofMinutes(10);
    var request =
        EnrollmentGrantRequest.builder()
            .workerId(workerId)
            .tokenSha256(tokenDigest)
            .lifetime(lifetime)
            .build();

    var result = repository.createGrant(request);

    assertThat(result)
        .isInstanceOfSatisfying(
            GrantCreationResult.Created.class,
            created -> {
              var grant = created.grant();
              assertThat(grant.workerId()).isEqualTo(workerId);
              assertThat(grant.trustBundle())
                  .isEqualTo(new PublicTrustBundleRef(trust.installationId(), 1L));
              assertThat(grant.expiresAt()).isEqualTo(grant.createdAt().plus(lifetime));
              assertThat(created.publicTrustBundle()).isEqualTo(trust.activeBundle());
              assertThat(
                      dsl.select(
                              TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID,
                              TRANSCODE_ENROLLMENT_GRANT.WORKER_ID,
                              TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION,
                              TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256)
                          .from(TRANSCODE_ENROLLMENT_GRANT)
                          .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(grant.grantId()))
                          .fetchSingle())
                  .satisfies(
                      stored -> {
                        assertThat(stored.value1()).isEqualTo(trust.installationId());
                        assertThat(stored.value2()).isEqualTo(workerId);
                        assertThat(stored.value3()).isEqualTo(1L);
                        assertThat(stored.value4()).containsExactly(tokenDigest.bytes());
                      });
            });
  }

  @Test
  @DisplayName("Should retain enrollment grant when worker and token digest are retried")
  void shouldRetainEnrollmentGrantWhenWorkerAndTokenDigestAreRetried() {
    bootstrapTrust();
    var request =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(new Sha256Digest(new byte[32]))
            .lifetime(Duration.ofNanos(1_000))
            .build();
    var created = (GrantCreationResult.Created) repository.createGrant(request);

    var retried = repository.createGrant(request);

    assertThat(retried)
        .isInstanceOfSatisfying(
            GrantCreationResult.Retained.class,
            retained -> {
              assertThat(retained.grant()).isEqualTo(created.grant());
              assertThat(retained.publicTrustBundle()).isEqualTo(created.publicTrustBundle());
            });
    assertThat(dsl.fetchCount(TRANSCODE_ENROLLMENT_GRANT)).isOne();
  }

  @Test
  @DisplayName("Should reject token digest collision without retaining another worker")
  void shouldRejectTokenDigestCollisionWithoutRetainingAnotherWorker() {
    bootstrapTrust();
    var tokenDigest = new Sha256Digest(new byte[32]);
    var firstRequest =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(tokenDigest)
            .lifetime(Duration.ofMinutes(10))
            .build();
    repository.createGrant(firstRequest);
    var conflictingRequest =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(tokenDigest)
            .lifetime(Duration.ofMinutes(10))
            .build();

    var result = repository.createGrant(conflictingRequest);

    assertThat(result)
        .isEqualTo(new GrantCreationResult.Conflict(GrantCreationConflict.TOKEN_DIGEST_IN_USE));
    assertThat(dsl.fetchCount(TRANSCODE_ENROLLMENT_GRANT)).isOne();
    assertThat(dsl.fetchCount(TRANSCODE_WORKER_IDENTITY)).isOne();
  }

  @Test
  @DisplayName("Should preserve preexisting worker identity when token digest collides")
  void shouldPreservePreexistingWorkerIdentityWhenTokenDigestCollides() {
    bootstrapTrust();
    var originalWorkerId = UUID.randomUUID();
    var preexistingWorkerId = UUID.randomUUID();
    var claimedDigest = new Sha256Digest(new byte[32]);
    var otherDigestBytes = new byte[32];
    otherDigestBytes[0] = 1;
    repository.createGrant(grantRequest(originalWorkerId, claimedDigest));
    repository.createGrant(grantRequest(preexistingWorkerId, new Sha256Digest(otherDigestBytes)));

    var result = repository.createGrant(grantRequest(preexistingWorkerId, claimedDigest));

    assertThat(result)
        .isEqualTo(new GrantCreationResult.Conflict(GrantCreationConflict.TOKEN_DIGEST_IN_USE));
    assertThat(dsl.fetchCount(TRANSCODE_ENROLLMENT_GRANT)).isEqualTo(2);
    assertThat(dsl.fetchCount(TRANSCODE_WORKER_IDENTITY)).isEqualTo(2);
  }

  @Test
  @DisplayName("Should create exactly one grant when token digest collides concurrently")
  void shouldCreateExactlyOneGrantWhenTokenDigestCollidesConcurrently() throws Exception {
    bootstrapTrust();
    var tokenDigest = new Sha256Digest(new byte[32]);
    var firstRequest =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(tokenDigest)
            .lifetime(Duration.ofMinutes(10))
            .build();
    var secondRequest =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(tokenDigest)
            .lifetime(Duration.ofMinutes(10))
            .build();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return repository.createGrant(firstRequest);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return repository.createGrant(secondRequest);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertThat(results).filteredOn(GrantCreationResult.Created.class::isInstance).hasSize(1);
      assertThat(results)
          .filteredOn(GrantCreationResult.Conflict.class::isInstance)
          .containsExactly(
              new GrantCreationResult.Conflict(GrantCreationConflict.TOKEN_DIGEST_IN_USE));
    }
    assertThat(dsl.fetchCount(TRANSCODE_ENROLLMENT_GRANT)).isOne();
    assertThat(dsl.fetchCount(TRANSCODE_WORKER_IDENTITY)).isOne();
  }

  @Test
  @DisplayName("Should retain original public trust bundle when retry follows bundle activation")
  void shouldRetainOriginalPublicTrustBundleWhenRetryFollowsBundleActivation() {
    var trust = bootstrapTrust();
    var request =
        EnrollmentGrantRequest.builder()
            .workerId(UUID.randomUUID())
            .tokenSha256(new Sha256Digest(new byte[32]))
            .lifetime(Duration.ofMinutes(10))
            .build();
    var created = (GrantCreationResult.Created) repository.createGrant(request);
    activateSecondBundle(trust.activeBundle());

    var retried = (GrantCreationResult.Retained) repository.createGrant(request);

    assertThat(retried.grant()).isEqualTo(created.grant());
    assertThat(retried.publicTrustBundle()).isEqualTo(created.publicTrustBundle());
    assertThat(retried.publicTrustBundle().version()).isEqualTo(1L);
  }

  private EnrollmentGrantRequest grantRequest(UUID workerId, Sha256Digest tokenDigest) {
    return EnrollmentGrantRequest.builder()
        .workerId(workerId)
        .tokenSha256(tokenDigest)
        .lifetime(Duration.ofMinutes(10))
        .build();
  }

  private com.streamarr.server.services.streaming.trust.InstallationTrust bootstrapTrust() {
    return trustFixture.bootstrap();
  }

  private void activateSecondBundle(
      com.streamarr.server.services.streaming.trust.PublicTrustBundle bundle) {
    dsl.transaction(
        configuration -> {
          var transaction = DSL.using(configuration);
          transaction
              .insertInto(TRANSCODE_PUBLIC_TRUST_BUNDLE)
              .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID, bundle.installationId())
              .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION, 2L)
              .execute();
          insertCertificate(
              transaction,
              bundle.installationId(),
              TranscodeTrustCertificateKind.TRUST_ANCHOR,
              bundle.trustAnchors().getFirst());
          insertCertificate(
              transaction,
              bundle.installationId(),
              TranscodeTrustCertificateKind.ISSUER,
              bundle.issuers().getFirst());
          insertCertificate(
              transaction,
              bundle.installationId(),
              TranscodeTrustCertificateKind.REVOCATION_SIGNER,
              bundle.revocationSigners().getFirst());
          transaction
              .update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
              .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, 2L)
              .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT, DSL.currentOffsetDateTime())
              .execute();
        });
  }

  private void insertCertificate(
      DSLContext transaction,
      UUID installationId,
      TranscodeTrustCertificateKind kind,
      X509Certificate certificate) {
    var certificateDer = encoded(certificate);
    transaction
        .insertInto(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID, installationId)
        .set(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION, 2L)
        .set(TRANSCODE_TRUST_CERTIFICATE.KIND, kind)
        .set(TRANSCODE_TRUST_CERTIFICATE.ORDINAL, (short) 0)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, certificateDer)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256, sha256(certificateDer))
        .execute();
  }

  private byte[] encoded(X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
