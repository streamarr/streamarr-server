package com.streamarr.server.services.streaming.trust;

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
import com.streamarr.server.repositories.streaming.trust.CertificateAuthoritySigningLeaseRepository;
import com.streamarr.server.repositories.streaming.trust.InstallationTrustRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.jooq.Fields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Installation Trust Bootstrap Integration Tests")
class InstallationTrustBootstrapIT extends AbstractIntegrationTest {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(5);

  @Autowired private InstallationTrustRepository trustRepository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private DSLContext dsl;

  @TempDir Path directory;

  private Path secretPath;

  @BeforeEach
  void resetTrustAuthority() throws Exception {
    secretPath = directory.resolve("authority.p12");
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
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
  @DisplayName("Should durably create one installation authority and public bundle")
  void shouldDurablyCreateOneInstallationAuthorityAndPublicBundle() {
    var trust = service(UUID.randomUUID()).bootstrap();

    assertThat(Files.isRegularFile(secretPath)).isTrue();
    assertThat(trust.installationId()).isEqualTo(trustRepository.installationId());
    assertThat(trust.activeBundle().version()).isEqualTo(1L);
    assertThat(trust.activeBundle().trustAnchors()).hasSize(1);
    assertThat(trust.activeBundle().issuers()).hasSize(1);
    assertThat(trust.activeBundle().revocationSigners()).hasSize(1);
    assertThat(trust.bootstrapRootSha256()).hasSize(32);
    assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isEqualTo(1);
    assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isEqualTo(3);
    assertThat(trustRepository.findInitialized()).contains(trust);
    var deleteBundle = dsl.deleteFrom(TRANSCODE_PUBLIC_TRUST_BUNDLE);
    assertThatThrownBy(deleteBundle::execute)
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            List.of(
                    TRANSCODE_INSTALLATION,
                    TRANSCODE_ACTIVE_TRUST_BUNDLE,
                    TRANSCODE_PUBLIC_TRUST_BUNDLE,
                    TRANSCODE_TRUST_CERTIFICATE,
                    TRANSCODE_CA_SIGNING_LEASE)
                .stream()
                .flatMap(Fields::fieldStream)
                .map(field -> field.getName().toLowerCase(java.util.Locale.ROOT)))
        .noneMatch(
            name -> name.contains("private") || name.contains("token") || name.contains("secret"));
  }

  @Test
  @DisplayName("Should converge on the same authority when replicas bootstrap concurrently")
  void shouldConvergeOnSameAuthorityWhenReplicasBootstrapConcurrently() throws Exception {
    var start = new CountDownLatch(1);
    var trusts = new CopyOnWriteArrayList<InstallationTrust>();
    var failures = new CopyOnWriteArrayList<Throwable>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var service : List.of(service(UUID.randomUUID()), service(UUID.randomUUID()))) {
        executor.submit(
            () -> {
              start.await();
              try {
                trusts.add(service.bootstrap());
              } catch (Throwable failure) {
                failures.add(failure);
              }
              return null;
            });
      }
      start.countDown();
    }

    assertThat(failures).isEmpty();
    assertThat(trusts).hasSize(2).allMatch(trusts.getFirst()::equals);
    assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isEqualTo(1);
    assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isEqualTo(3);
  }

  @Test
  @DisplayName("Should adopt a complete authority file after a lost database publication")
  void shouldAdoptCompleteAuthorityFileAfterLostDatabasePublication() {
    var material =
        new BuiltInCertificateAuthority()
            .create(trustRepository.installationId(), Instant.parse("2026-07-12T12:00:00Z"));
    new CertificateAuthorityStore(secretPath).createIfAbsent(material);

    var trust = service(UUID.randomUUID()).bootstrap();

    assertThat(encoded(trust.activeBundle().trustAnchors().getFirst()))
        .isEqualTo(encoded(material.rootCertificate()));
    assertThat(trustRepository.findInitialized()).contains(trust);
  }

  @Test
  @DisplayName("Should refuse an expired bootstrap authority before publication")
  void shouldRefuseExpiredBootstrapAuthorityBeforePublication() {
    var expired =
        new BuiltInCertificateAuthority()
            .create(
                trustRepository.installationId(),
                trustRepository.databaseTime().minus(Duration.ofDays(31)));
    new CertificateAuthorityStore(secretPath).createIfAbsent(expired);
    var bootstrapService = service(UUID.randomUUID());

    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasCauseInstanceOf(CertificateExpiredException.class);
    assertThat(trustRepository.findInitialized()).isEmpty();
    assertThat(dsl.fetchCount(TRANSCODE_PUBLIC_TRUST_BUNDLE)).isZero();
    assertThat(dsl.fetchCount(TRANSCODE_TRUST_CERTIFICATE)).isZero();
  }

  @Test
  @DisplayName("Should fail closed when initialized authority secret is missing")
  void shouldFailClosedWhenInitializedAuthoritySecretMissing() throws Exception {
    service(UUID.randomUUID()).bootstrap();
    Files.delete(secretPath);
    var bootstrapService = service(UUID.randomUUID());

    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("missing");
    assertThat(secretPath).doesNotExist();
  }

  @Test
  @DisplayName("Should reject an authority restored from another installation")
  void shouldRejectAuthorityRestoredFromAnotherInstallation() throws Exception {
    service(UUID.randomUUID()).bootstrap();
    Files.delete(secretPath);
    var foreign =
        new BuiltInCertificateAuthority()
            .create(UUID.fromString("872667d4-32d8-4624-b216-92788a966bd7"), Instant.now());
    new CertificateAuthorityStore(secretPath).createIfAbsent(foreign);
    var bootstrapService = service(UUID.randomUUID());

    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("installation");
  }

  @Test
  @DisplayName("Should reject a substituted authority for the same installation")
  void shouldRejectSubstitutedAuthorityForSameInstallation() throws Exception {
    var original = service(UUID.randomUUID()).bootstrap();
    Files.delete(secretPath);
    var substitute =
        new BuiltInCertificateAuthority()
            .create(trustRepository.installationId(), trustRepository.databaseTime());
    new CertificateAuthorityStore(secretPath).createIfAbsent(substitute);
    var bootstrapService = service(UUID.randomUUID());

    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("fingerprint");
    assertThat(trustRepository.findInitialized()).contains(original);
  }

  @Test
  @DisplayName("Should reject a database fingerprint that no longer matches the mounted secret")
  void shouldRejectDatabaseFingerprintThatNoLongerMatchesMountedSecret() {
    service(UUID.randomUUID()).bootstrap();
    dsl.update(TRANSCODE_INSTALLATION)
        .set(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256, new byte[32])
        .execute();
    var bootstrapService = service(UUID.randomUUID());

    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("fingerprint");
  }

  @Test
  @DisplayName("Should reject a substituted public certificate on startup")
  void shouldRejectSubstitutedPublicCertificateOnStartup() {
    var original = service(UUID.randomUUID()).bootstrap();
    var substitute =
        new BuiltInCertificateAuthority()
            .create(trustRepository.installationId(), trustRepository.databaseTime());
    var substituteIssuer = encoded(substitute.issuerCertificate());
    dsl.update(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, substituteIssuer)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256, sha256(substituteIssuer))
        .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.ISSUER))
        .execute();
    var bootstrapService = service(UUID.randomUUID());

    assertThat(trustRepository.findInitialized()).isPresent();
    assertThatThrownBy(bootstrapService::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("public trust bundle");
    assertThat(trustRepository.findInitialized()).isNotEqualTo(java.util.Optional.of(original));
  }

  private InstallationTrustBootstrapService service(UUID ownerId) {
    return InstallationTrustBootstrapService.builder()
        .trustRepository(trustRepository)
        .signingLeaseRepository(signingLeaseRepository)
        .authorityStore(new CertificateAuthorityStore(secretPath))
        .certificateAuthority(new BuiltInCertificateAuthority())
        .ownerId(ownerId)
        .leaseDuration(LEASE_DURATION)
        .build();
  }

  private static byte[] encoded(java.security.cert.X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (java.security.cert.CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
