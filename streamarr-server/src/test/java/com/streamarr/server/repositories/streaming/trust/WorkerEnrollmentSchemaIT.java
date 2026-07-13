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
import com.streamarr.server.jooq.generated.enums.TranscodeTrustCertificateKind;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
@DisplayName("Worker Enrollment Schema Integration Tests")
class WorkerEnrollmentSchemaIT extends AbstractIntegrationTest {

  private static final Duration SIGNING_LEASE = Duration.ofSeconds(30);

  @Autowired private InstallationTrustRepository trustRepository;
  @Autowired private CertificateAuthoritySigningLeaseRepository signingLeaseRepository;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void resetTrustState() {
    dsl.deleteFrom(DSL.table(DSL.name("transcode_worker_certificate_issuance"))).execute();
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

  @Test
  @DisplayName("Should persist fixed certificate issuance claim against exact enrollment grant")
  void shouldPersistFixedCertificateIssuanceClaimAgainstExactEnrollmentGrant() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);

    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    var grantIdField = DSL.field(DSL.name("grant_id"), UUID.class);
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var bundleVersionField = DSL.field(DSL.name("trust_bundle_version"), Long.class);
    var subjectPublicKeyDigestField =
        DSL.field(DSL.name("subject_public_key_sha256"), byte[].class);
    var issuerDigestField = DSL.field(DSL.name("issuer_certificate_sha256"), byte[].class);
    var serialNumberField = DSL.field(DSL.name("serial_number"), byte[].class);
    var fencingEpochField = DSL.field(DSL.name("signing_fencing_epoch"), Long.class);

    var stored =
        dsl.select(
                grantIdField,
                workerIdField,
                bundleVersionField,
                subjectPublicKeyDigestField,
                issuerDigestField,
                serialNumberField,
                fencingEpochField)
            .from(issuanceTable)
            .where(requestIdField.eq(requestId))
            .fetchSingle();
    assertThat(stored.value1()).isEqualTo(fixture.grantId());
    assertThat(stored.value2()).isEqualTo(fixture.workerId());
    assertThat(stored.value3()).isEqualTo(1L);
    assertThat(stored.value4()).containsExactly(new byte[32]);
    assertThat(stored.value5()).containsExactly(fixture.issuerDigest().bytes());
    assertThat(stored.value6()).containsExactly(1);
    assertThat(stored.value7()).isEqualTo(fixture.issuanceLease().fencingEpoch());
  }

  @Test
  @DisplayName("Should allow only one certificate issuance claim per enrollment grant")
  void shouldAllowOnlyOneCertificateIssuanceClaimPerEnrollmentGrant() {
    var fixture = issuanceFixture();
    insertIssuanceClaim(fixture, UUID.randomUUID());
    var secondRequestId = UUID.randomUUID();

    assertThatThrownBy(() -> insertIssuanceClaim(fixture, secondRequestId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_transcode_worker_certificate_issuance_grant");
  }

  @Test
  @DisplayName("Should reject certificate issuance claim when worker differs from exact grant")
  void shouldRejectCertificateIssuanceClaimWhenWorkerDiffersFromExactGrant() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    var differentWorkerId = UUID.randomUUID();

    assertThatThrownBy(() -> insertIssuanceClaim(fixture, requestId, differentWorkerId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_transcode_worker_certificate_issuance_grant");
  }

  @Test
  @DisplayName("Should reject certificate issuance claim when issuer is outside pinned bundle")
  void shouldRejectCertificateIssuanceClaimWhenIssuerIsOutsidePinnedBundle() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var issuerDigestField = DSL.field(DSL.name("issuer_certificate_sha256"), byte[].class);
    var replaceIssuer =
        dsl.update(issuanceTable)
            .set(issuerDigestField, new byte[32])
            .where(requestIdField.eq(requestId));

    assertThatThrownBy(replaceIssuer::execute)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_transcode_worker_certificate_issuance_issuer");
  }

  @Test
  @DisplayName("Should reject certificate issuance claim when certificate role is not issuer")
  void shouldRejectCertificateIssuanceClaimWhenCertificateRoleIsNotIssuer() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var issuerDigestField = DSL.field(DSL.name("issuer_certificate_sha256"), byte[].class);
    var replaceIssuerWithTrustAnchor =
        dsl.update(issuanceTable)
            .set(issuerDigestField, fixture.trustAnchorDigest().bytes())
            .where(requestIdField.eq(requestId));

    assertThatThrownBy(replaceIssuerWithTrustAnchor::execute)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_transcode_worker_certificate_issuance_issuer");
  }

  @Test
  @DisplayName("Should reject malformed fixed certificate issuance parameters")
  void shouldRejectMalformedFixedCertificateIssuanceParameters() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var subjectPublicKeyField = DSL.field(DSL.name("subject_public_key_info_der"), byte[].class);
    var subjectPublicKeyDigestField =
        DSL.field(DSL.name("subject_public_key_sha256"), byte[].class);
    var serialNumberField = DSL.field(DSL.name("serial_number"), byte[].class);
    var profileVersionField = DSL.field(DSL.name("certificate_profile_version"), Short.class);
    var notAfterField = DSL.field(DSL.name("not_after"), OffsetDateTime.class);
    var fencingEpochField = DSL.field(DSL.name("signing_fencing_epoch"), Long.class);

    assertConstraintViolation(
        dsl.update(issuanceTable)
            .set(subjectPublicKeyField, new byte[0])
            .where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_public_key_der");
    assertConstraintViolation(
        dsl.update(issuanceTable)
            .set(subjectPublicKeyDigestField, new byte[31])
            .where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_public_key_digest");
    assertConstraintViolation(
        dsl.update(issuanceTable)
            .set(serialNumberField, new byte[] {0})
            .where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_serial");
    assertConstraintViolation(
        dsl.update(issuanceTable)
            .set(profileVersionField, (short) 2)
            .where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_profile");
    assertConstraintViolation(
        dsl.update(issuanceTable)
            .set(notAfterField, fixture.databaseTime().atOffset(ZoneOffset.UTC))
            .where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_validity");
    assertConstraintViolation(
        dsl.update(issuanceTable).set(fencingEpochField, 0L).where(requestIdField.eq(requestId)),
        "chk_transcode_worker_certificate_issuance_claim");
  }

  @Test
  @DisplayName("Should reject worker certificate serial when positive DER exceeds twenty octets")
  void shouldRejectWorkerCertificateSerialWhenPositiveDerExceedsTwentyOctets() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var serialNumberField = DSL.field(DSL.name("serial_number"), byte[].class);
    var twentyByteHighBitSerial = new byte[20];
    twentyByteHighBitSerial[0] = (byte) 0x80;
    var replaceSerial =
        dsl.update(issuanceTable)
            .set(serialNumberField, twentyByteHighBitSerial)
            .where(requestIdField.eq(requestId));

    assertConstraintViolation(replaceSerial, "chk_transcode_worker_certificate_issuance_serial");
  }

  @Test
  @DisplayName("Should reject worker certificate validity with subsecond precision")
  void shouldRejectWorkerCertificateValidityWithSubsecondPrecision() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var notBeforeField = DSL.field(DSL.name("not_before"), OffsetDateTime.class);
    var subsecondNotBefore =
        fixture
            .databaseTime()
            .truncatedTo(ChronoUnit.SECONDS)
            .plusNanos(123_456_000)
            .atOffset(ZoneOffset.UTC);
    var replaceValidity =
        dsl.update(issuanceTable)
            .set(notBeforeField, subsecondNotBefore)
            .where(requestIdField.eq(requestId));

    assertConstraintViolation(
        replaceValidity, "chk_transcode_worker_certificate_issuance_validity");
  }

  @Test
  @DisplayName("Should store certificate issuance completion only as one complete result")
  void shouldStoreCertificateIssuanceCompletionOnlyAsOneCompleteResult() {
    var fixture = issuanceFixture();
    var requestId = UUID.randomUUID();
    insertIssuanceClaim(fixture, requestId);
    var issuanceTable = DSL.table(DSL.name("transcode_worker_certificate_issuance"));
    var requestIdField = DSL.field(DSL.name("request_id"), UUID.class);
    var certificateDerField = DSL.field(DSL.name("certificate_der"), byte[].class);
    var certificateDigestField = DSL.field(DSL.name("certificate_sha256"), byte[].class);
    var completedAtField = DSL.field(DSL.name("completed_at"), OffsetDateTime.class);
    var partialCompletion =
        dsl.update(issuanceTable)
            .set(certificateDerField, new byte[] {48, 0})
            .where(requestIdField.eq(requestId));
    var completionWithoutTimestamp =
        dsl.update(issuanceTable)
            .set(certificateDerField, new byte[] {48, 0})
            .set(certificateDigestField, new byte[32])
            .where(requestIdField.eq(requestId));

    assertConstraintViolation(
        partialCompletion, "chk_transcode_worker_certificate_issuance_completion");
    assertConstraintViolation(
        completionWithoutTimestamp, "chk_transcode_worker_certificate_issuance_completion");

    var completedAfterGrantExpiry =
        fixture.databaseTime().plus(Duration.ofMinutes(11)).atOffset(ZoneOffset.UTC);
    var completed =
        dsl.update(issuanceTable)
            .set(certificateDerField, new byte[] {48, 0})
            .set(certificateDigestField, new byte[32])
            .set(completedAtField, completedAfterGrantExpiry)
            .where(requestIdField.eq(requestId))
            .execute();

    assertThat(completed).isOne();
  }

  @Test
  @DisplayName("Should reject certificate issuance when issuer serial is already retained")
  void shouldRejectCertificateIssuanceWhenIssuerSerialIsAlreadyRetained() {
    var firstFixture = issuanceFixture();
    var secondFixture = additionalGrantFixture(firstFixture);
    insertIssuanceClaim(firstFixture, UUID.randomUUID());
    var secondRequestId = UUID.randomUUID();

    assertThatThrownBy(() -> insertIssuanceClaim(secondFixture, secondRequestId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_transcode_worker_certificate_issuance_serial");
  }

  private IssuanceFixture issuanceFixture() {
    var installationId = trustRepository.installationId();
    var databaseTime = trustRepository.databaseTime();
    var material = new BuiltInCertificateAuthority().create(installationId, databaseTime);
    var bootstrapLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.BOOTSTRAP, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    assertThat(
            trustRepository.publishInitial(bootstrapLease, InitialTrustPublication.from(material)))
        .isTrue();
    assertThat(signingLeaseRepository.release(bootstrapLease)).isTrue();
    var issuanceLease =
        signingLeaseRepository
            .tryAcquire(CertificateAuthorityOperation.ISSUANCE, UUID.randomUUID(), SIGNING_LEASE)
            .orElseThrow();
    var issuerDigest =
        dsl.select(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
            .from(TRANSCODE_TRUST_CERTIFICATE)
            .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.ISSUER))
            .fetchSingle(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256);
    var trustAnchorDigest =
        dsl.select(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
            .from(TRANSCODE_TRUST_CERTIFICATE)
            .where(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(TranscodeTrustCertificateKind.TRUST_ANCHOR))
            .fetchSingle(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256);
    var workerId = UUID.randomUUID();
    var grantId = UUID.randomUUID();
    var installationIdField = DSL.field(DSL.name("installation_id"), UUID.class);
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    dsl.insertInto(DSL.table(DSL.name("transcode_worker_identity")))
        .columns(installationIdField, workerIdField)
        .values(installationId, workerId)
        .execute();
    dsl.insertInto(DSL.table(DSL.name("transcode_enrollment_grant")))
        .columns(
            DSL.field(DSL.name("grant_id"), UUID.class),
            installationIdField,
            workerIdField,
            DSL.field(DSL.name("trust_bundle_version"), Long.class),
            DSL.field(DSL.name("token_sha256"), byte[].class),
            DSL.field(DSL.name("expires_at"), OffsetDateTime.class))
        .values(
            grantId,
            installationId,
            workerId,
            1L,
            new byte[32],
            databaseTime.plus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC))
        .execute();
    return IssuanceFixture.builder()
        .installationId(installationId)
        .workerId(workerId)
        .grantId(grantId)
        .issuanceLease(issuanceLease)
        .issuerDigest(new Sha256Digest(issuerDigest))
        .trustAnchorDigest(new Sha256Digest(trustAnchorDigest))
        .databaseTime(databaseTime)
        .build();
  }

  private void insertIssuanceClaim(IssuanceFixture fixture, UUID requestId) {
    insertIssuanceClaim(fixture, requestId, fixture.workerId());
  }

  private void insertIssuanceClaim(IssuanceFixture fixture, UUID requestId, UUID workerId) {
    dsl.insertInto(DSL.table(DSL.name("transcode_worker_certificate_issuance")))
        .columns(
            DSL.field(DSL.name("request_id"), UUID.class),
            DSL.field(DSL.name("grant_id"), UUID.class),
            DSL.field(DSL.name("installation_id"), UUID.class),
            DSL.field(DSL.name("worker_id"), UUID.class),
            DSL.field(DSL.name("trust_bundle_version"), Long.class),
            DSL.field(DSL.name("subject_public_key_info_der"), byte[].class),
            DSL.field(DSL.name("subject_public_key_sha256"), byte[].class),
            DSL.field(DSL.name("issuer_certificate_sha256"), byte[].class),
            DSL.field(DSL.name("serial_number"), byte[].class),
            DSL.field(DSL.name("certificate_profile_version"), Short.class),
            DSL.field(DSL.name("not_before"), OffsetDateTime.class),
            DSL.field(DSL.name("not_after"), OffsetDateTime.class),
            DSL.field(DSL.name("signing_fencing_epoch"), Long.class))
        .values(
            requestId,
            fixture.grantId(),
            fixture.installationId(),
            workerId,
            1L,
            new byte[] {48, 0},
            new byte[32],
            fixture.issuerDigest().bytes(),
            new byte[] {1},
            (short) 1,
            fixture.databaseTime().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC),
            fixture
                .databaseTime()
                .plus(Duration.ofDays(7))
                .truncatedTo(ChronoUnit.SECONDS)
                .atOffset(ZoneOffset.UTC),
            fixture.issuanceLease().fencingEpoch())
        .execute();
  }

  private IssuanceFixture additionalGrantFixture(IssuanceFixture fixture) {
    var workerId = UUID.randomUUID();
    var grantId = UUID.randomUUID();
    var tokenDigest = new byte[32];
    tokenDigest[0] = 1;
    var installationIdField = DSL.field(DSL.name("installation_id"), UUID.class);
    var workerIdField = DSL.field(DSL.name("worker_id"), UUID.class);
    dsl.insertInto(DSL.table(DSL.name("transcode_worker_identity")))
        .columns(installationIdField, workerIdField)
        .values(fixture.installationId(), workerId)
        .execute();
    dsl.insertInto(DSL.table(DSL.name("transcode_enrollment_grant")))
        .columns(
            DSL.field(DSL.name("grant_id"), UUID.class),
            installationIdField,
            workerIdField,
            DSL.field(DSL.name("trust_bundle_version"), Long.class),
            DSL.field(DSL.name("token_sha256"), byte[].class),
            DSL.field(DSL.name("expires_at"), OffsetDateTime.class))
        .values(
            grantId,
            fixture.installationId(),
            workerId,
            1L,
            tokenDigest,
            fixture.databaseTime().plus(Duration.ofMinutes(10)).atOffset(ZoneOffset.UTC))
        .execute();
    return IssuanceFixture.builder()
        .installationId(fixture.installationId())
        .workerId(workerId)
        .grantId(grantId)
        .issuanceLease(fixture.issuanceLease())
        .issuerDigest(fixture.issuerDigest())
        .trustAnchorDigest(fixture.trustAnchorDigest())
        .databaseTime(fixture.databaseTime())
        .build();
  }

  private void assertConstraintViolation(org.jooq.Query query, String constraint) {
    assertThatThrownBy(query::execute)
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(constraint);
  }

  @Builder
  private record IssuanceFixture(
      UUID installationId,
      UUID workerId,
      UUID grantId,
      CertificateSigningLease issuanceLease,
      Sha256Digest issuerDigest,
      Sha256Digest trustAnchorDigest,
      Instant databaseTime) {}
}
