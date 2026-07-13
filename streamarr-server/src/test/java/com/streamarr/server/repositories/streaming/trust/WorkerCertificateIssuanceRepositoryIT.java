package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerCertificateIssuance.TRANSCODE_WORKER_CERTIFICATE_ISSUANCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceParameters;
import com.streamarr.server.services.streaming.trust.CertificateSerialNumber;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.CertificateValidity;
import com.streamarr.server.services.streaming.trust.EnrollmentCertificateRequest;
import com.streamarr.server.services.streaming.trust.EnrollmentGrantRequest;
import com.streamarr.server.services.streaming.trust.GrantCreationResult;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import com.streamarr.server.services.streaming.trust.SubjectPublicKeyInfo;
import com.streamarr.server.services.streaming.trust.WorkerCertificateProfile;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    repository.claim(second, first.issuanceLease());
    var firstRequest = first.claimRequest().request();
    var crossWired =
        CertificateIssuanceClaimRequest.builder()
            .request(
                EnrollmentCertificateRequest.builder()
                    .requestId(second.request().requestId())
                    .workerId(firstRequest.workerId())
                    .tokenSha256(firstRequest.tokenSha256())
                    .subjectPublicKeyInfo(firstRequest.subjectPublicKeyInfo())
                    .build())
            .proposedParameters(first.parameters())
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
    var grantLocked = new CountDownLatch(1);
    var releaseGrant = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        DSL.using(configuration)
                            .select(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID)
                            .from(TRANSCODE_ENROLLMENT_GRANT)
                            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                            .forUpdate()
                            .fetchSingle();
                        grantLocked.countDown();
                        assertThat(releaseGrant.await(10, TimeUnit.SECONDS)).isTrue();
                      }));
      assertThat(grantLocked.await(5, TimeUnit.SECONDS)).isTrue();
      var claim = executor.submit(() -> repository.claim(fixture.claimRequest(), expiringLease));
      try {
        awaitGrantLockWait();
        awaitDatabaseTime(expiringLease.leaseUntil());
      } finally {
        releaseGrant.countDown();
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
    var grantLocked = new CountDownLatch(1);
    var expireGrant = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  dsl.transaction(
                      configuration -> {
                        var transaction = DSL.using(configuration);
                        transaction
                            .select(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID)
                            .from(TRANSCODE_ENROLLMENT_GRANT)
                            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                            .forUpdate()
                            .fetchSingle();
                        grantLocked.countDown();
                        assertThat(expireGrant.await(5, TimeUnit.SECONDS)).isTrue();
                        transaction
                            .update(TRANSCODE_ENROLLMENT_GRANT)
                            .set(
                                TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
                                CertificateSigningLeaseGuard.statementTimestamp())
                            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(fixture.grantId()))
                            .execute();
                      }));
      assertThat(grantLocked.await(5, TimeUnit.SECONDS)).isTrue();
      var claim =
          executor.submit(() -> repository.claim(fixture.claimRequest(), fixture.issuanceLease()));
      try {
        awaitGrantLockWait();
      } finally {
        expireGrant.countDown();
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

  private void awaitGrantLockWait() {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      var blocked =
          dsl.fetchExists(
              DSL.selectOne()
                  .from(DSL.table(DSL.name("pg_catalog", "pg_stat_activity")))
                  .where(DSL.field(DSL.name("wait_event_type"), String.class).eq("Lock"))
                  .and(
                      DSL.field(DSL.name("query"), String.class)
                          .contains("transcode_enrollment_grant")));
      if (blocked) {
        return;
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    throw new AssertionError("Enrollment grant claim did not wait for the row lock");
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
    var trust = bootstrapTrust();
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
    var subjectPublicKeyInfo =
        SubjectPublicKeyInfo.from(subjectKeyGenerator.generateKeyPair().getPublic());
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
        .subjectPublicKeyInfo(subjectPublicKeyInfo)
        .parameters(parameters)
        .claimRequest(claimRequest)
        .issuanceLease(issuanceLease)
        .build();
  }

  private com.streamarr.server.services.streaming.trust.InstallationTrust bootstrapTrust() {
    return trustFixture.bootstrapAndRelease();
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
      SubjectPublicKeyInfo subjectPublicKeyInfo,
      CertificateIssuanceParameters parameters,
      CertificateIssuanceClaimRequest claimRequest,
      CertificateSigningLease issuanceLease) {}
}
