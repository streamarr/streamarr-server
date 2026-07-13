package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;
import static com.streamarr.server.jooq.generated.tables.TranscodeInstallation.TRANSCODE_INSTALLATION;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Worker Enrollment Schema Integration Tests")
class WorkerEnrollmentSchemaIT extends AbstractIntegrationTest {

  private static final Duration SIGNING_LEASE = Duration.ofSeconds(30);

  @Autowired private InstallationTrustRepository trustRepository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void resetTrustState() {
    dsl.deleteFrom(DSL.table(DSL.name("transcode_enrollment_grant"))).execute();
    dsl.deleteFrom(DSL.table(DSL.name("transcode_worker_identity"))).execute();
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

  @Test
  @DisplayName("Should persist enrollment grant bound to exact worker and public trust bundle")
  void shouldPersistEnrollmentGrantBoundToExactWorkerAndPublicTrustBundle() {
    var installationId = trustRepository.installationId();
    var databaseTime = trustRepository.databaseTime();
    var material = new BuiltInCertificateAuthority().create(installationId, databaseTime);
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    assertThat(trustRepository.publishInitial(lease, InitialTrustPublication.from(material)))
        .isTrue();

    var workerId = UUID.randomUUID();
    var grantId = UUID.randomUUID();
    var tokenDigest = new byte[32];
    var workerTable = DSL.table(DSL.name("transcode_worker_identity"));
    var grantTable = DSL.table(DSL.name("transcode_enrollment_grant"));
    var installationIdField = DSL.field(DSL.name("installation_id"), UUID.class);
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    var grantIdField = DSL.field(DSL.name("grant_id"), UUID.class);
    var bundleVersionField = DSL.field(DSL.name("trust_bundle_version"), Long.class);
    var tokenDigestField = DSL.field(DSL.name("token_sha256"), byte[].class);
    var expiresAtField = DSL.field(DSL.name("expires_at"), OffsetDateTime.class);

    dsl.insertInto(workerTable)
        .columns(installationIdField, workerIdField)
        .values(installationId, workerId)
        .execute();
    dsl.insertInto(grantTable)
        .columns(
            grantIdField,
            installationIdField,
            workerIdField,
            bundleVersionField,
            tokenDigestField,
            expiresAtField)
        .values(
            grantId,
            installationId,
            workerId,
            1L,
            tokenDigest,
            databaseTime.plus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC))
        .execute();

    var stored =
        dsl.select(installationIdField, workerIdField, bundleVersionField, tokenDigestField)
            .from(grantTable)
            .where(grantIdField.eq(grantId))
            .fetchSingle();
    assertThat(stored.value1()).isEqualTo(installationId);
    assertThat(stored.value2()).isEqualTo(workerId);
    assertThat(stored.value3()).isEqualTo(1L);
    assertThat(stored.value4()).containsExactly(tokenDigest);
  }

  @Test
  @DisplayName("Should reject enrollment grant when public trust bundle does not exist")
  void shouldRejectEnrollmentGrantWhenPublicTrustBundleDoesNotExist() {
    var installationId = trustRepository.installationId();
    var databaseTime = trustRepository.databaseTime();
    var material = new BuiltInCertificateAuthority().create(installationId, databaseTime);
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    assertThat(trustRepository.publishInitial(lease, InitialTrustPublication.from(material)))
        .isTrue();

    var workerId = UUID.randomUUID();
    var workerTable = DSL.table(DSL.name("transcode_worker_identity"));
    var grantTable = DSL.table(DSL.name("transcode_enrollment_grant"));
    var installationIdField = DSL.field(DSL.name("installation_id"), UUID.class);
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    var grantIdField = DSL.field(DSL.name("grant_id"), UUID.class);
    var bundleVersionField = DSL.field(DSL.name("trust_bundle_version"), Long.class);
    var tokenDigestField = DSL.field(DSL.name("token_sha256"), byte[].class);
    var expiresAtField = DSL.field(DSL.name("expires_at"), OffsetDateTime.class);
    dsl.insertInto(workerTable)
        .columns(installationIdField, workerIdField)
        .values(installationId, workerId)
        .execute();

    var insertMissingBundleGrant =
        dsl.insertInto(grantTable)
            .columns(
                grantIdField,
                installationIdField,
                workerIdField,
                bundleVersionField,
                tokenDigestField,
                expiresAtField)
            .values(
                UUID.randomUUID(),
                installationId,
                workerId,
                2L,
                new byte[32],
                databaseTime.plus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC));

    assertThatThrownBy(insertMissingBundleGrant::execute)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_transcode_enrollment_grant_bundle");
  }

  @Test
  @DisplayName("Should consume enrollment grant once while retaining its binding")
  void shouldConsumeEnrollmentGrantOnceWhileRetainingItsBinding() {
    var installationId = trustRepository.installationId();
    var databaseTime = trustRepository.databaseTime();
    var material = new BuiltInCertificateAuthority().create(installationId, databaseTime);
    var lease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    assertThat(trustRepository.publishInitial(lease, InitialTrustPublication.from(material)))
        .isTrue();

    var workerId = UUID.randomUUID();
    var grantId = UUID.randomUUID();
    var workerTable = DSL.table(DSL.name("transcode_worker_identity"));
    var grantTable = DSL.table(DSL.name("transcode_enrollment_grant"));
    var installationIdField = DSL.field(DSL.name("installation_id"), UUID.class);
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    var grantIdField = DSL.field(DSL.name("grant_id"), UUID.class);
    var bundleVersionField = DSL.field(DSL.name("trust_bundle_version"), Long.class);
    var tokenDigestField = DSL.field(DSL.name("token_sha256"), byte[].class);
    var expiresAtField = DSL.field(DSL.name("expires_at"), OffsetDateTime.class);
    var consumedAtField = DSL.field(DSL.name("consumed_at"), OffsetDateTime.class);
    var expiration = databaseTime.plus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC);
    dsl.insertInto(workerTable)
        .columns(installationIdField, workerIdField)
        .values(installationId, workerId)
        .execute();
    dsl.insertInto(grantTable)
        .columns(
            grantIdField,
            installationIdField,
            workerIdField,
            bundleVersionField,
            tokenDigestField,
            expiresAtField)
        .values(grantId, installationId, workerId, 1L, new byte[32], expiration)
        .execute();

    var consumptionTime = databaseTime.plusSeconds(1).atOffset(ZoneOffset.UTC);
    var firstConsumption =
        dsl.update(grantTable)
            .set(consumedAtField, consumptionTime)
            .where(grantIdField.eq(grantId))
            .and(consumedAtField.isNull())
            .and(expiresAtField.gt(consumptionTime))
            .execute();
    var secondConsumption =
        dsl.update(grantTable)
            .set(consumedAtField, consumptionTime)
            .where(grantIdField.eq(grantId))
            .and(consumedAtField.isNull())
            .and(expiresAtField.gt(consumptionTime))
            .execute();

    assertThat(firstConsumption).isOne();
    assertThat(secondConsumption).isZero();
    assertThat(
            dsl.select(bundleVersionField)
                .from(grantTable)
                .where(grantIdField.eq(grantId))
                .fetchSingle(bundleVersionField))
        .isEqualTo(1L);
  }
}
