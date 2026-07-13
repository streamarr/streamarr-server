package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerCertificateIssuance.TRANSCODE_WORKER_CERTIFICATE_ISSUANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityMaterial;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletion;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletionRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletionResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceParameters;
import com.streamarr.server.services.streaming.trust.CertificateSerialNumber;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.CertificateValidity;
import com.streamarr.server.services.streaming.trust.EncodedWorkerCertificate;
import com.streamarr.server.services.streaming.trust.EnrollmentCertificateRequest;
import com.streamarr.server.services.streaming.trust.EnrollmentGrantRequest;
import com.streamarr.server.services.streaming.trust.GrantCreationResult;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.PublicTrustBundle;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import com.streamarr.server.services.streaming.trust.SubjectPublicKeyInfo;
import com.streamarr.server.services.streaming.trust.WorkerCertificateProfile;
import com.streamarr.server.services.streaming.trust.WorkerCertificateTestFixture;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Worker Certificate Issuance Repository Integration Tests")
class WorkerCertificateIssuanceRepositoryIT extends AbstractIntegrationTest {

  private static final Duration SIGNING_LEASE = Duration.ofSeconds(30);

  @Autowired private InstallationTrustRepository trustRepository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private WorkerEnrollmentRepository enrollmentRepository;
  @Autowired private WorkerCertificateIssuanceRepository repository;
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
  @DisplayName("Should claim exact worker certificate parameters under live issuance lease")
  void shouldClaimExactWorkerCertificateParametersUnderLiveIssuanceLease() throws Exception {
    var fixture = issuanceFixture();

    var result = repository.claim(fixture.claimRequest(), fixture.issuanceLease());

    assertThat(result)
        .isInstanceOfSatisfying(
            CertificateIssuanceClaimResult.ReadyToSign.class,
            ready -> {
              assertThat(ready.requestId()).isEqualTo(fixture.requestId());
              assertThat(ready.workerId()).isEqualTo(fixture.workerId());
              assertThat(ready.trustBundle()).isEqualTo(fixture.trustBundle());
              assertThat(ready.subjectPublicKeyInfo()).isEqualTo(fixture.subjectPublicKeyInfo());
              assertThat(ready.parameters()).isEqualTo(fixture.parameters());
              assertThat(ready.signingFencingEpoch())
                  .isEqualTo(fixture.issuanceLease().fencingEpoch());
            });
  }

  @Test
  @DisplayName("Should reject certificate work under non-issuance signing lease")
  void shouldRejectCertificateWorkUnderNonIssuanceSigningLease() throws Exception {
    var fixture = issuanceFixture();
    var current = fixture.issuanceLease();
    var rotationLease =
        CertificateSigningLease.builder()
            .operation(CertificateAuthorityOperation.ROTATION)
            .ownerId(current.ownerId())
            .fencingEpoch(current.fencingEpoch())
            .databaseTime(current.databaseTime())
            .leaseUntil(current.leaseUntil())
            .build();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());

    var claim = repository.claim(fixture.claimRequest(), rotationLease);
    var completion =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(rotationLease.fencingEpoch())
                .certificate(certificate)
                .build(),
            rotationLease);

    assertThat(claim)
        .isEqualTo(
            new CertificateIssuanceClaimResult.Rejected(
                CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE));
    assertThat(completion)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE));
    assertThat(
            dsl.fetchExists(
                DSL.selectOne()
                    .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
                    .where(
                        TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))))
        .isFalse();
  }

  @Test
  @DisplayName("Should reject certificate claim when enrollment credential is not bound to worker")
  void shouldRejectCertificateClaimWhenEnrollmentCredentialIsNotBoundToWorker() throws Exception {
    var fixture = issuanceFixture();
    var request = fixture.claimRequest().request();
    var unknownToken =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(request.requestId())
                    .workerId(request.workerId())
                    .tokenSha256(
                        new Sha256Digest(
                            MessageDigest.getInstance("SHA-256").digest(new byte[] {99})))
                    .subjectPublicKeyInfo(request.subjectPublicKeyInfo())
                    .build())
            .proposedParameters(fixture.parameters())
            .build();
    var wrongWorker =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(request.requestId())
                    .workerId(UUID.randomUUID())
                    .tokenSha256(request.tokenSha256())
                    .subjectPublicKeyInfo(request.subjectPublicKeyInfo())
                    .build())
            .proposedParameters(fixture.parameters())
            .build();

    var unknownTokenResult = repository.claim(unknownToken, fixture.issuanceLease());
    var wrongWorkerResult = repository.claim(wrongWorker, fixture.issuanceLease());

    var rejection =
        new CertificateIssuanceClaimResult.Rejected(
            CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID);
    assertThat(unknownTokenResult).isEqualTo(rejection);
    assertThat(wrongWorkerResult).isEqualTo(rejection);
  }

  @Test
  @DisplayName("Should reject certificate work after signing lease is released")
  void shouldRejectCertificateWorkAfterSigningLeaseIsReleased() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();

    var claim = repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var completion =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(claimed.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            fixture.issuanceLease());

    assertThat(claim)
        .isEqualTo(
            new CertificateIssuanceClaimResult.Rejected(
                CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE));
    assertThat(completion)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject certificate completion without retained claim")
  void shouldRejectCertificateCompletionWithoutRetainedClaim() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());

    var result =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(UUID.randomUUID())
                .signingFencingEpoch(claimed.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CLAIM_UNAVAILABLE));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject expired signing lease before inspecting certificate claim")
  void shouldRejectExpiredSigningLeaseBeforeInspectingCertificateClaim() throws Exception {
    var fixture = issuanceFixture();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var expiringLease =
        signingLeaseRepository.renew(fixture.issuanceLease(), Duration.ofMillis(100)).orElseThrow();
    awaitDatabaseTime(expiringLease.leaseUntil());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(expiringLease.fencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, expiringLease);

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE));
  }

  @Test
  @DisplayName("Should atomically store worker certificate and consume enrollment grant")
  void shouldAtomicallyStoreWorkerCertificateAndConsumeEnrollmentGrant() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isInstanceOfSatisfying(
            CertificateIssuanceCompletionResult.Stored.class,
            stored -> {
              assertThat(stored.certificate().requestId()).isEqualTo(fixture.requestId());
              assertThat(stored.certificate().workerId()).isEqualTo(fixture.workerId());
              assertThat(stored.certificate().certificate()).isEqualTo(certificate);
              assertThat(stored.certificate().trustBundle()).isEqualTo(fixture.publicTrustBundle());
            });
    assertThat(
            dsl.select(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT)
                .from(TRANSCODE_ENROLLMENT_GRANT)
                .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                .fetchSingle(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT))
        .isNotNull();
    assertThat(
            dsl.select(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER)
                .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
                .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
                .fetchSingle(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER))
        .isEqualTo(certificate.der());
  }

  @Test
  @DisplayName("Should recover exact completed certificate without signing lease")
  void shouldRecoverExactCompletedCertificateWithoutSigningLease() throws Exception {
    var fixture = issuanceFixture();
    var stored = completeCertificate(fixture);
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    dsl.update(TRANSCODE_ENROLLMENT_GRANT)
        .set(
            TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
            CertificateSigningLeaseGuard.statementTimestamp())
        .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
        .execute();

    var recovered = repository.findCompleted(fixture.claimRequest().request());

    assertThat(recovered).contains(stored.certificate());
  }

  @Test
  @DisplayName("Should not expose incomplete certificate claim through recovery lookup")
  void shouldNotExposeIncompleteCertificateClaimThroughRecoveryLookup() throws Exception {
    var fixture = issuanceFixture();
    repository.claim(fixture.claimRequest(), fixture.issuanceLease());

    var recovered = repository.findCompleted(fixture.claimRequest().request());

    assertThat(recovered).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when incomplete subject public key digest is corrupted")
  void shouldFailClosedWhenIncompleteSubjectPublicKeyDigestIsCorrupted() throws Exception {
    var fixture = issuanceFixture();
    repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    dsl.update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_SHA256, new byte[32])
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
        .execute();

    assertThatThrownBy(() -> repository.claim(fixture.claimRequest(), fixture.issuanceLease()))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessage("Stored worker subject public key digest does not match exact DER");
  }

  @Test
  @DisplayName("Should require every request binding to recover completed certificate")
  void shouldRequireEveryRequestBindingToRecoverCompletedCertificate() throws Exception {
    var fixture = issuanceFixture();
    completeCertificate(fixture);
    var request = fixture.claimRequest().request();
    var otherKeyGenerator = KeyPairGenerator.getInstance("EC");
    otherKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var mismatchedRequests =
        List.of(
            EnrollmentCertificateRequest.builder()
                .requestId(UUID.randomUUID())
                .workerId(request.workerId())
                .tokenSha256(request.tokenSha256())
                .subjectPublicKeyInfo(request.subjectPublicKeyInfo())
                .build(),
            EnrollmentCertificateRequest.builder()
                .requestId(request.requestId())
                .workerId(UUID.randomUUID())
                .tokenSha256(request.tokenSha256())
                .subjectPublicKeyInfo(request.subjectPublicKeyInfo())
                .build(),
            EnrollmentCertificateRequest.builder()
                .requestId(request.requestId())
                .workerId(request.workerId())
                .tokenSha256(
                    new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(new byte[] {99})))
                .subjectPublicKeyInfo(request.subjectPublicKeyInfo())
                .build(),
            EnrollmentCertificateRequest.builder()
                .requestId(request.requestId())
                .workerId(request.workerId())
                .tokenSha256(request.tokenSha256())
                .subjectPublicKeyInfo(
                    SubjectPublicKeyInfo.from(otherKeyGenerator.generateKeyPair().getPublic()))
                .build());

    assertThat(mismatchedRequests)
        .allSatisfy(mismatched -> assertThat(repository.findCompleted(mismatched)).isEmpty());
  }

  @Test
  @DisplayName("Should fail closed when completed certificate digest is corrupted")
  void shouldFailClosedWhenCompletedCertificateDigestIsCorrupted() throws Exception {
    var fixture = issuanceFixture();
    completeCertificate(fixture);
    dsl.update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_SHA256, new byte[32])
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
        .execute();

    assertThatThrownBy(() -> repository.findCompleted(fixture.claimRequest().request()))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessage("Stored worker certificate digest does not match exact DER");
  }

  @Test
  @DisplayName("Should fail closed when completed subject public key digest is corrupted")
  void shouldFailClosedWhenCompletedSubjectPublicKeyDigestIsCorrupted() throws Exception {
    var fixture = issuanceFixture();
    completeCertificate(fixture);
    dsl.update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_SHA256, new byte[32])
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
        .execute();

    assertThatThrownBy(() -> repository.claim(fixture.claimRequest(), fixture.issuanceLease()))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessage("Stored worker subject public key digest does not match exact DER");
  }

  @Test
  @DisplayName("Should fail closed when completed certificate DER is corrupted")
  void shouldFailClosedWhenCompletedCertificateDerIsCorrupted() throws Exception {
    var fixture = issuanceFixture();
    completeCertificate(fixture);
    var corruptedDer = new byte[] {1};
    dsl.update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER, corruptedDer)
        .set(
            TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_SHA256,
            MessageDigest.getInstance("SHA-256").digest(corruptedDer))
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
        .execute();

    assertThatThrownBy(() -> repository.findCompleted(fixture.claimRequest().request()))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessage("Stored worker certificate is invalid");
  }

  @Test
  @DisplayName("Should recover public trust bundle version pinned by completed issuance")
  void shouldRecoverPublicTrustBundleVersionPinnedByCompletedIssuance() throws Exception {
    var fixture = issuanceFixture();
    var stored = completeCertificate(fixture);
    activateCopiedTrustBundle(fixture.publicTrustBundle().installationId(), 2L);
    assertThat(trustRepository.findInitialized().orElseThrow().activeBundle().version())
        .isEqualTo(2L);

    var recovered = repository.findCompleted(fixture.claimRequest().request());

    assertThat(recovered).contains(stored.certificate());
    assertThat(recovered.orElseThrow().trustBundle().version()).isEqualTo(1L);
  }

  private void activateCopiedTrustBundle(UUID installationId, long version) {
    dsl.transaction(
        configuration -> {
          var transaction = DSL.using(configuration);
          transaction
              .insertInto(TRANSCODE_PUBLIC_TRUST_BUNDLE)
              .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID, installationId)
              .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION, version)
              .execute();
          transaction
              .insertInto(
                  TRANSCODE_TRUST_CERTIFICATE,
                  TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID,
                  TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION,
                  TRANSCODE_TRUST_CERTIFICATE.KIND,
                  TRANSCODE_TRUST_CERTIFICATE.ORDINAL,
                  TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER,
                  TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
              .select(
                  transaction
                      .select(
                          TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID,
                          DSL.inline(version),
                          TRANSCODE_TRUST_CERTIFICATE.KIND,
                          TRANSCODE_TRUST_CERTIFICATE.ORDINAL,
                          TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER,
                          TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
                      .from(TRANSCODE_TRUST_CERTIFICATE)
                      .where(TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID.eq(installationId))
                      .and(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION.eq(1L)))
              .execute();
          transaction
              .update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
              .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, version)
              .set(
                  TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT,
                  CertificateSigningLeaseGuard.statementTimestamp())
              .execute();
        });
  }

  @Test
  @DisplayName("Should return completed certificate when exact claim retries without live lease")
  void shouldReturnCompletedCertificateWhenExactClaimRetriesWithoutLiveLease() throws Exception {
    var fixture = issuanceFixture();
    var stored = completeCertificate(fixture);
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();

    var retried = repository.claim(fixture.claimRequest(), fixture.issuanceLease());

    assertThat(retried)
        .isEqualTo(new CertificateIssuanceClaimResult.Completed(stored.certificate()));
  }

  private CertificateIssuanceCompletionResult.Stored completeCertificate(IssuanceFixture fixture)
      throws Exception {
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    return (CertificateIssuanceCompletionResult.Stored)
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(claimed.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            fixture.issuanceLease());
  }

  @Test
  @DisplayName("Should complete claimed certificate after enrollment grant expires")
  void shouldCompleteClaimedCertificateAfterEnrollmentGrantExpires() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    dsl.update(TRANSCODE_ENROLLMENT_GRANT)
        .set(
            TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
            CertificateSigningLeaseGuard.statementTimestamp())
        .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
        .execute();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result).isInstanceOf(CertificateIssuanceCompletionResult.Stored.class);
    assertThat(storedCertificateDer(fixture.requestId())).isEqualTo(certificate.der());
  }

  @Test
  @DisplayName("Should reject stale completion epoch after certificate claim is reclaimed")
  void shouldRejectStaleCompletionEpochAfterCertificateClaimIsReclaimed() throws Exception {
    var fixture = issuanceFixture();
    var firstClaim =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    var nextLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    var reclaimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), nextLease);
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());

    var staleResult =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(firstClaim.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            nextLease);

    assertThat(staleResult)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CLAIM_UNAVAILABLE));
    assertIssuanceIncomplete(fixture);

    var reclaimedResult =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(reclaimed.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            nextLease);

    assertThat(reclaimedResult).isInstanceOf(CertificateIssuanceCompletionResult.Stored.class);
    assertThat(storedCertificateDer(fixture.requestId())).isEqualTo(certificate.der());
  }

  @Test
  @DisplayName("Should reject old claim completion under newer signing lease")
  void shouldRejectOldClaimCompletionUnderNewerSigningLease() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    var nextLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());

    var result =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(claimed.signingFencingEpoch())
                .certificate(certificate)
                .build(),
            nextLease);

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CLAIM_UNAVAILABLE));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should roll back grant consumption when certificate persistence fails")
  void shouldRollBackGrantConsumptionWhenCertificatePersistenceFails() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    dsl.update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(
            TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CLAIMED_AT,
            trustRepository.databaseTime().plus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
        .execute();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    assertThatThrownBy(() -> repository.complete(completion, fixture.issuanceLease()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should roll back completion when signing lease expires during lock wait")
  void shouldRollBackCompletionWhenSigningLeaseExpiresDuringLockWait() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();
    var expiringLease =
        signingLeaseRepository.renew(fixture.issuanceLease(), Duration.ofSeconds(2)).orElseThrow();
    var barrier = GrantLockBarrier.create();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdGrantLock(fixture.grantId(), barrier));
      barrier.awaitAcquired();
      var result = executor.submit(() -> repository.complete(completion, expiringLease));
      try {
        awaitGrantLockWait();
        awaitDatabaseTime(expiringLease.leaseUntil());
      } finally {
        barrier.release();
      }

      assertThat(result.get(5, TimeUnit.SECONDS))
          .isEqualTo(
              new CertificateIssuanceCompletionResult.Rejected(
                  CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE));
      blocker.get(5, TimeUnit.SECONDS);
    }
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject worker certificate bound to another public key")
  void shouldRejectWorkerCertificateBoundToAnotherPublicKey() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var otherKeyGenerator = KeyPairGenerator.getInstance("EC");
    otherKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(
                    defaultWorkerContext(fixture)
                        .subjectPublicKey(otherKeyGenerator.generateKeyPair().getPublic())
                        .build())
                .getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject worker certificate with another serial number")
  void shouldRejectWorkerCertificateWithAnotherSerialNumber() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(
                    defaultWorkerContext(fixture)
                        .parameters(
                            certificateParameters(
                                fixture,
                                new CertificateSerialNumber(
                                    fixture
                                        .parameters()
                                        .serialNumber()
                                        .value()
                                        .add(BigInteger.ONE)),
                                fixture.parameters().validity()))
                        .build())
                .getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject worker certificate with another validity interval")
  void shouldRejectWorkerCertificateWithAnotherValidityInterval() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var validity = fixture.parameters().validity();
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(
                    defaultWorkerContext(fixture)
                        .parameters(
                            certificateParameters(
                                fixture,
                                fixture.parameters().serialNumber(),
                                new CertificateValidity(
                                    validity.notBefore().plusSeconds(1), validity.notAfter())))
                        .build())
                .getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject worker certificate naming another worker")
  void shouldRejectWorkerCertificateNamingAnotherWorker() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).workerId(UUID.randomUUID()).build())
                .getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject worker certificate signed by another issuer")
  void shouldRejectWorkerCertificateSignedByAnotherIssuer() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var otherAuthority =
        new BuiltInCertificateAuthority()
            .create(fixture.publicTrustBundle().installationId(), trustRepository.databaseTime());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(
                    defaultWorkerContext(fixture).authorityMaterial(otherAuthority).build())
                .getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();

    var result = repository.complete(completion, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should fail closed when pinned issuer certificate digest is corrupted")
  void shouldFailClosedWhenPinnedIssuerCertificateDigestIsCorrupted() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();
    var lease = fixture.issuanceLease();
    dsl.update(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, new byte[] {1})
        .where(
            TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID.eq(
                fixture.publicTrustBundle().installationId()))
        .and(
            TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256.eq(
                fixture.parameters().issuerCertificateSha256().bytes()))
        .execute();

    assertThatThrownBy(() -> repository.complete(completion, lease))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessage("Stored issuing certificate digest does not match exact DER");
    assertIssuanceIncomplete(fixture);
  }

  @Test
  @DisplayName("Should reject mismatched certificate after issuance already completed")
  void shouldRejectMismatchedCertificateAfterIssuanceAlreadyCompleted() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var storedCertificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    repository.complete(
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(storedCertificate)
            .build(),
        fixture.issuanceLease());
    var otherKeyGenerator = KeyPairGenerator.getInstance("EC");
    otherKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var mismatchedCertificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(
                    defaultWorkerContext(fixture)
                        .subjectPublicKey(otherKeyGenerator.generateKeyPair().getPublic())
                        .build())
                .getEncoded());

    var result =
        repository.complete(
            CertificateIssuanceCompletion.builder()
                .requestId(fixture.requestId())
                .signingFencingEpoch(claimed.signingFencingEpoch())
                .certificate(mismatchedCertificate)
                .build(),
            fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceCompletionResult.Rejected(
                CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH));
    assertThat(storedCertificateDer(fixture.requestId())).isEqualTo(storedCertificate.der());
  }

  @Test
  @DisplayName("Should retain first valid certificate when completion retries with new signature")
  void shouldRetainFirstValidCertificateWhenCompletionRetriesWithNewSignature() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var shape = defaultWorkerContext(fixture).build();
    var firstCertificate = EncodedWorkerCertificate.fromDer(workerCertificate(shape).getEncoded());
    var retryCertificate = EncodedWorkerCertificate.fromDer(workerCertificate(shape).getEncoded());
    assertThat(retryCertificate).isNotEqualTo(firstCertificate);
    var firstCompletion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(firstCertificate)
            .build();
    var retryCompletion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(retryCertificate)
            .build();

    var first = repository.complete(firstCompletion, fixture.issuanceLease());
    var retried = repository.complete(retryCompletion, fixture.issuanceLease());

    assertThat(retried).isEqualTo(first);
    assertThat(storedCertificateDer(fixture.requestId())).isEqualTo(firstCertificate.der());
  }

  @Test
  @DisplayName("Should converge concurrent valid completions on one stored certificate")
  void shouldConvergeConcurrentValidCompletionsOnOneStoredCertificate() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var shape = defaultWorkerContext(fixture).build();
    var firstCertificate = EncodedWorkerCertificate.fromDer(workerCertificate(shape).getEncoded());
    var secondCertificate = EncodedWorkerCertificate.fromDer(workerCertificate(shape).getEncoded());
    assertThat(secondCertificate).isNotEqualTo(firstCertificate);
    var firstCompletion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(firstCertificate)
            .build();
    var secondCompletion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(secondCertificate)
            .build();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var barrier = new CompletionBarrier(ready, start);

    CertificateIssuanceCompletionResult firstResult;
    CertificateIssuanceCompletionResult secondResult;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> completeAfterStart(firstCompletion, fixture.issuanceLease(), barrier));
      var second =
          executor.submit(
              () -> completeAfterStart(secondCompletion, fixture.issuanceLease(), barrier));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult = first.get(5, TimeUnit.SECONDS);
      secondResult = second.get(5, TimeUnit.SECONDS);
    }

    assertThat(secondResult).isEqualTo(firstResult);
    assertThat(firstResult)
        .isInstanceOfSatisfying(
            CertificateIssuanceCompletionResult.Stored.class,
            stored -> {
              var winner = stored.certificate().certificate();
              assertThat(winner).isIn(firstCertificate, secondCertificate);
              assertThat(storedCertificateDer(fixture.requestId())).isEqualTo(winner.der());
            });
  }

  @Test
  @DisplayName("Should recover completion committed while exact claim waits for signing lease")
  void shouldRecoverCompletionCommittedWhileExactClaimWaitsForSigningLease() throws Exception {
    var fixture = issuanceFixture();
    var claimed =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var certificate =
        EncodedWorkerCertificate.fromDer(
            workerCertificate(defaultWorkerContext(fixture).build()).getEncoded());
    var completion =
        CertificateIssuanceCompletion.builder()
            .requestId(fixture.requestId())
            .signingFencingEpoch(claimed.signingFencingEpoch())
            .certificate(certificate)
            .build();
    var barrier = GrantLockBarrier.create();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdGrantLock(fixture.grantId(), barrier));
      barrier.awaitAcquired();
      var completed =
          executor.submit(() -> repository.complete(completion, fixture.issuanceLease()));
      awaitGrantLockWait();
      var retriedClaim =
          executor.submit(() -> repository.claim(fixture.claimRequest(), fixture.issuanceLease()));
      try {
        awaitSigningLeaseLockWait();
      } finally {
        barrier.release();
      }
      var stored = (CertificateIssuanceCompletionResult.Stored) completed.get(5, TimeUnit.SECONDS);

      assertThat(retriedClaim.get(5, TimeUnit.SECONDS))
          .isEqualTo(new CertificateIssuanceClaimResult.Completed(stored.certificate()));
      blocker.get(5, TimeUnit.SECONDS);
    }
  }

  private CertificateIssuanceCompletionResult completeAfterStart(
      CertificateIssuanceCompletion completion,
      CertificateSigningLease lease,
      CompletionBarrier barrier)
      throws InterruptedException {
    barrier.ready().countDown();
    assertThat(barrier.start().await(5, TimeUnit.SECONDS)).isTrue();
    return repository.complete(completion, lease);
  }

  private byte[] storedCertificateDer(UUID requestId) {
    return dsl.select(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER)
        .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(requestId))
        .fetchSingle(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER);
  }

  private void assertIssuanceIncomplete(IssuanceFixture fixture) {
    assertThat(
            dsl.select(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT)
                .from(TRANSCODE_ENROLLMENT_GRANT)
                .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                .fetchSingle(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT))
        .isNull();
    var issuance =
        dsl.select(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER,
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_SHA256,
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.COMPLETED_AT)
            .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
            .fetchSingle();
    assertThat(issuance.value1()).isNull();
    assertThat(issuance.value2()).isNull();
    assertThat(issuance.value3()).isNull();
  }

  @Test
  @DisplayName("Should retain first certificate parameters when exact request retries")
  void shouldRetainFirstCertificateParametersWhenExactRequestRetries() throws Exception {
    var fixture = issuanceFixture();
    var first = repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var original = (CertificateIssuanceClaimResult.ReadyToSign) first;
    var originalValidity = fixture.parameters().validity();
    var differentParameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(fixture.parameters().issuerCertificateSha256())
            .serialNumber(new CertificateSerialNumber(new BigInteger("9876543210987654321")))
            .profile(WorkerCertificateProfile.V1)
            .validity(
                new CertificateValidity(
                    originalValidity.notBefore().plus(Duration.ofDays(1)),
                    originalValidity.notAfter().plus(Duration.ofDays(1))))
            .build();
    var retry =
        CertificateIssuanceClaimRequest.builder()
            .request(fixture.claimRequest().request())
            .proposedParameters(differentParameters)
            .build();

    var retried = repository.claim(retry, fixture.issuanceLease());

    assertThat(retried).isEqualTo(original);
  }

  @Test
  @DisplayName("Should reject exact certificate request retry bound to another public key")
  void shouldRejectExactCertificateRequestRetryBoundToAnotherPublicKey() throws Exception {
    var fixture = issuanceFixture();
    repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var otherKeyGenerator = KeyPairGenerator.getInstance("EC");
    otherKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var request = fixture.claimRequest().request();
    var retry =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(request.requestId())
                    .workerId(request.workerId())
                    .tokenSha256(request.tokenSha256())
                    .subjectPublicKeyInfo(
                        SubjectPublicKeyInfo.from(otherKeyGenerator.generateKeyPair().getPublic()))
                    .build())
            .proposedParameters(fixture.parameters())
            .build();

    var result = repository.claim(retry, fixture.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceClaimResult.Rejected(
                CertificateIssuanceClaimRejection.REQUEST_CONFLICT));
  }

  @Test
  @DisplayName("Should reclaim fixed certificate claim under newer issuance lease")
  void shouldReclaimFixedCertificateClaimUnderNewerIssuanceLease() throws Exception {
    var fixture = issuanceFixture();
    var first =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    var nextLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();

    var reclaimed = repository.claim(fixture.claimRequest(), nextLease);

    assertThat(reclaimed)
        .isInstanceOfSatisfying(
            CertificateIssuanceClaimResult.ReadyToSign.class,
            ready -> {
              assertThat(ready.parameters()).isEqualTo(first.parameters());
              assertThat(ready.signingFencingEpoch()).isEqualTo(nextLease.fencingEpoch());
            });
  }

  @Test
  @DisplayName("Should roll back reclaimed claim when signing lease expires during lock wait")
  void shouldRollBackReclaimedClaimWhenSigningLeaseExpiresDuringLockWait() throws Exception {
    var fixture = issuanceFixture();
    var first =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    var expiringLease =
        signingLeaseRepository
            .tryAcquire(
                CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), Duration.ofSeconds(2))
            .orElseThrow();
    var barrier = GrantLockBarrier.create();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdGrantLock(fixture.grantId(), barrier));
      barrier.awaitAcquired();
      var claim = executor.submit(() -> repository.claim(fixture.claimRequest(), expiringLease));
      try {
        awaitGrantLockWait();
        awaitDatabaseTime(expiringLease.leaseUntil());
      } finally {
        barrier.release();
      }

      assertThat(claim.get(5, TimeUnit.SECONDS))
          .isEqualTo(
              new CertificateIssuanceClaimResult.Rejected(
                  CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE));
      blocker.get(5, TimeUnit.SECONDS);
    }
    assertThat(
            dsl.select(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SIGNING_FENCING_EPOCH)
                .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
                .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId()))
                .fetchSingle(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SIGNING_FENCING_EPOCH))
        .isEqualTo(first.signingFencingEpoch());
  }

  @Test
  @DisplayName("Should reclaim fixed certificate claim after enrollment grant expires")
  void shouldReclaimFixedCertificateClaimAfterEnrollmentGrantExpires() throws Exception {
    var fixture = issuanceFixture();
    var first =
        (CertificateIssuanceClaimResult.ReadyToSign)
            repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    assertThat(signingLeaseRepository.release(fixture.issuanceLease())).isTrue();
    dsl.update(TRANSCODE_ENROLLMENT_GRANT)
        .set(
            TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
            fixture.grantCreatedAt().plusNanos(1_000).atOffset(ZoneOffset.UTC))
        .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
        .execute();
    var nextLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();

    var reclaimed = repository.claim(fixture.claimRequest(), nextLease);

    assertThat(reclaimed)
        .isInstanceOfSatisfying(
            CertificateIssuanceClaimResult.ReadyToSign.class,
            ready -> {
              assertThat(ready.parameters()).isEqualTo(first.parameters());
              assertThat(ready.signingFencingEpoch()).isEqualTo(nextLease.fencingEpoch());
            });
  }

  @Test
  @DisplayName("Should request new parameters when issuer serial proposal collides")
  void shouldRequestNewParametersWhenIssuerSerialProposalCollides() throws Exception {
    var fixture = issuanceFixture();
    repository.claim(fixture.claimRequest(), fixture.issuanceLease());
    var collidingRequest = additionalClaimRequest(fixture.parameters(), (byte) 2);

    var result = repository.claim(collidingRequest, fixture.issuanceLease());

    assertThat(result).isInstanceOf(CertificateIssuanceClaimResult.RetryWithNewParameters.class);
  }

  @Test
  @DisplayName("Should reject request id already bound to another enrollment grant")
  void shouldRejectRequestIdAlreadyBoundToAnotherEnrollmentGrant() throws Exception {
    var first = issuanceFixture();
    repository.claim(first.claimRequest(), first.issuanceLease());
    var secondParameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(first.parameters().issuerCertificateSha256())
            .serialNumber(new CertificateSerialNumber(new BigInteger("23456789012345678901")))
            .profile(first.parameters().profile())
            .validity(first.parameters().validity())
            .build();
    var second = additionalClaimRequest(secondParameters, (byte) 3);
    var secondRequest = second.request();
    var crossWired =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(first.requestId())
                    .workerId(secondRequest.workerId())
                    .tokenSha256(secondRequest.tokenSha256())
                    .subjectPublicKeyInfo(secondRequest.subjectPublicKeyInfo())
                    .build())
            .proposedParameters(secondParameters)
            .build();

    var result = repository.claim(crossWired, first.issuanceLease());

    assertThat(result)
        .isEqualTo(
            new CertificateIssuanceClaimResult.Rejected(
                CertificateIssuanceClaimRejection.REQUEST_CONFLICT));
  }

  @Test
  @DisplayName(
      "Should roll back first certificate claim when signing lease expires during lock wait")
  void shouldRollBackFirstCertificateClaimWhenSigningLeaseExpiresDuringLockWait() throws Exception {
    var fixture = issuanceFixture();
    var expiringLease =
        signingLeaseRepository.renew(fixture.issuanceLease(), Duration.ofSeconds(2)).orElseThrow();
    var barrier = GrantLockBarrier.create();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = executor.submit(() -> holdGrantLock(fixture.grantId(), barrier));
      barrier.awaitAcquired();
      var claim = executor.submit(() -> repository.claim(fixture.claimRequest(), expiringLease));
      try {
        awaitGrantLockWait();
        awaitDatabaseTime(expiringLease.leaseUntil());
      } finally {
        barrier.release();
      }

      assertThat(claim.get(5, TimeUnit.SECONDS))
          .isEqualTo(
              new CertificateIssuanceClaimResult.Rejected(
                  CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE));
      blocker.get(5, TimeUnit.SECONDS);
    }
    assertThat(
            dsl.fetchExists(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE,
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(fixture.requestId())))
        .isFalse();
    assertThat(
            dsl.select(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT)
                .from(TRANSCODE_ENROLLMENT_GRANT)
                .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                .fetchSingle(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT))
        .isNull();
  }

  @Test
  @DisplayName("Should reject first certificate claim when grant expires during lock wait")
  void shouldRejectFirstCertificateClaimWhenGrantExpiresDuringLockWait() throws Exception {
    var fixture = issuanceFixture();
    var barrier = GrantLockBarrier.create();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        var transaction = DSL.using(configuration);
                        lockGrantRow(transaction, fixture.grantId());
                        barrier.signalAcquired();
                        barrier.awaitRelease();
                        transaction
                            .update(TRANSCODE_ENROLLMENT_GRANT)
                            .set(
                                TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
                                CertificateSigningLeaseGuard.statementTimestamp())
                            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                            .execute();
                      }));
      barrier.awaitAcquired();
      var claim =
          executor.submit(() -> repository.claim(fixture.claimRequest(), fixture.issuanceLease()));
      try {
        awaitGrantLockWait();
      } finally {
        barrier.release();
      }

      assertThat(claim.get(5, TimeUnit.SECONDS))
          .isEqualTo(
              new CertificateIssuanceClaimResult.Rejected(
                  CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID));
      blocker.get(5, TimeUnit.SECONDS);
    }
  }

  private CertificateIssuanceClaimRequest additionalClaimRequest(
      CertificateIssuanceParameters parameters, byte tokenSeed) throws Exception {
    var workerId = UUID.randomUUID();
    var tokenDigest =
        new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(new byte[] {tokenSeed}));
    enrollmentRepository.createGrant(
        EnrollmentGrantRequest.builder()
            .workerId(workerId)
            .tokenSha256(tokenDigest)
            .lifetime(Duration.ofMinutes(10))
            .build());
    var subjectKeyGenerator = KeyPairGenerator.getInstance("EC");
    subjectKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    return CertificateIssuanceClaimRequest.builder()
        .request(
            EnrollmentCertificateRequest.builder()
                .requestId(UUID.randomUUID())
                .workerId(workerId)
                .tokenSha256(tokenDigest)
                .subjectPublicKeyInfo(
                    SubjectPublicKeyInfo.from(subjectKeyGenerator.generateKeyPair().getPublic()))
                .build())
        .proposedParameters(parameters)
        .build();
  }

  private void holdGrantLock(UUID grantId, GrantLockBarrier barrier) {
    dsl.transaction(
        configuration -> {
          lockGrantRow(DSL.using(configuration), grantId);
          barrier.signalAcquired();
          barrier.awaitRelease();
        });
  }

  private void lockGrantRow(DSLContext transaction, UUID grantId) {
    transaction
        .select(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID)
        .from(TRANSCODE_ENROLLMENT_GRANT)
        .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(grantId))
        .forUpdate()
        .fetchSingle();
  }

  private void awaitGrantLockWait() {
    awaitLockWait("transcode_enrollment_grant");
  }

  private void awaitSigningLeaseLockWait() {
    awaitLockWait("transcode_ca_signing_lease");
  }

  private void awaitLockWait(String tableName) {
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
    throw new AssertionError(tableName + " operation did not wait for the row lock");
  }

  private void awaitDatabaseTime(Instant deadline) {
    var timeout = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < timeout) {
      if (!trustRepository.databaseTime().isBefore(deadline)) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    throw new AssertionError("Database time did not reach signing lease expiry");
  }

  private IssuanceFixture issuanceFixture() throws Exception {
    var bootstrap = trustFixture.bootstrapResultAndRelease();
    var trust = bootstrap.trust();
    var workerId = UUID.randomUUID();
    var requestId = UUID.randomUUID();
    var tokenDigest = new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(new byte[] {1}));
    var created =
        (GrantCreationResult.Created)
            enrollmentRepository.createGrant(
                EnrollmentGrantRequest.builder()
                    .workerId(workerId)
                    .tokenSha256(tokenDigest)
                    .lifetime(Duration.ofMinutes(10))
                    .build());
    var subjectKeyGenerator = KeyPairGenerator.getInstance("EC");
    subjectKeyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var subjectPublicKey = subjectKeyGenerator.generateKeyPair().getPublic();
    var subjectPublicKeyInfo = SubjectPublicKeyInfo.from(subjectPublicKey);
    var notBefore = trustRepository.databaseTime().truncatedTo(ChronoUnit.SECONDS);
    var validity = new CertificateValidity(notBefore, notBefore.plus(Duration.ofDays(7)));
    var parameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(sha256(trust.activeBundle().issuers().getFirst()))
            .serialNumber(new CertificateSerialNumber(new BigInteger("12345678901234567890")))
            .profile(WorkerCertificateProfile.V1)
            .validity(validity)
            .build();
    var claimRequest =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(requestId)
                    .workerId(workerId)
                    .tokenSha256(tokenDigest)
                    .subjectPublicKeyInfo(subjectPublicKeyInfo)
                    .build())
            .proposedParameters(parameters)
            .build();
    var issuanceLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    return IssuanceFixture.builder()
        .grantId(created.grant().grantId())
        .grantCreatedAt(created.grant().createdAt())
        .requestId(requestId)
        .workerId(workerId)
        .trustBundle(created.grant().trustBundle())
        .publicTrustBundle(trust.activeBundle())
        .authorityMaterial(bootstrap.material())
        .subjectPublicKey(subjectPublicKey)
        .subjectPublicKeyInfo(subjectPublicKeyInfo)
        .parameters(parameters)
        .claimRequest(claimRequest)
        .issuanceLease(issuanceLease)
        .build();
  }

  private WorkerCertificateTestFixture.WorkerContext.WorkerContextBuilder defaultWorkerContext(
      IssuanceFixture fixture) {
    return WorkerCertificateTestFixture.WorkerContext.builder()
        .installationId(fixture.publicTrustBundle().installationId())
        .workerId(fixture.workerId())
        .authorityMaterial(fixture.authorityMaterial())
        .subjectPublicKey(fixture.subjectPublicKey())
        .parameters(fixture.parameters());
  }

  private CertificateIssuanceParameters certificateParameters(
      IssuanceFixture fixture, CertificateSerialNumber serialNumber, CertificateValidity validity) {
    return CertificateIssuanceParameters.builder()
        .issuerCertificateSha256(fixture.parameters().issuerCertificateSha256())
        .serialNumber(serialNumber)
        .profile(fixture.parameters().profile())
        .validity(validity)
        .build();
  }

  private X509Certificate workerCertificate(WorkerCertificateTestFixture.WorkerContext context)
      throws Exception {
    return WorkerCertificateTestFixture.certificate(
        WorkerCertificateTestFixture.validShape(context).build());
  }

  private Sha256Digest sha256(X509Certificate certificate) {
    try {
      return new Sha256Digest(
          MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Builder
  private record IssuanceFixture(
      UUID grantId,
      Instant grantCreatedAt,
      UUID requestId,
      UUID workerId,
      PublicTrustBundleRef trustBundle,
      PublicTrustBundle publicTrustBundle,
      CertificateAuthorityMaterial authorityMaterial,
      PublicKey subjectPublicKey,
      SubjectPublicKeyInfo subjectPublicKeyInfo,
      CertificateIssuanceParameters parameters,
      CertificateIssuanceClaimRequest claimRequest,
      CertificateSigningLease issuanceLease) {}

  private record CompletionBarrier(CountDownLatch ready, CountDownLatch start) {}

  private record GrantLockBarrier(CountDownLatch acquiredLatch, CountDownLatch releaseLatch) {

    static GrantLockBarrier create() {
      return new GrantLockBarrier(new CountDownLatch(1), new CountDownLatch(1));
    }

    void signalAcquired() {
      acquiredLatch.countDown();
    }

    void awaitAcquired() throws InterruptedException {
      assertThat(acquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    void awaitRelease() throws InterruptedException {
      assertThat(releaseLatch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    void release() {
      releaseLatch.countDown();
    }
  }
}
