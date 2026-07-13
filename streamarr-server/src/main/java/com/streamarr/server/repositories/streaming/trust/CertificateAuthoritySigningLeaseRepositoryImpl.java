package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;

import com.streamarr.server.jooq.generated.tables.records.TranscodeCaSigningLeaseRecord;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CertificateAuthoritySigningLeaseRepositoryImpl
    implements CertificateAuthoritySigningLeaseRepository {

  private static final Duration MAXIMUM_LEASE_DURATION = Duration.ofMinutes(5);

  private final DSLContext dsl;

  @Override
  public Optional<CertificateSigningLease> tryAcquire(
      CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
    requirePositive(duration);
    return dsl.transactionResult(
        configuration -> tryAcquire(DSL.using(configuration), operation, ownerId, duration));
  }

  private Optional<CertificateSigningLease> tryAcquire(
      DSLContext transaction,
      CertificateAuthorityOperation operation,
      UUID ownerId,
      Duration duration) {
    var current =
        transaction
            .selectFrom(TRANSCODE_CA_SIGNING_LEASE)
            .where(TRANSCODE_CA_SIGNING_LEASE.SINGLETON.isTrue())
            .forUpdate()
            .fetchSingle();
    var databaseTime = CertificateSigningLeaseGuard.databaseTime(transaction);
    if (current.getLeaseUntil() != null && current.getLeaseUntil().isAfter(databaseTime)) {
      return Optional.empty();
    }

    var leaseUntil = databaseTime.plus(duration);
    return transaction
        .update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.OPERATION, generated(operation))
        .set(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID, ownerId)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, leaseUntil)
        .set(
            TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH,
            TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH.plus(1L))
        .where(TRANSCODE_CA_SIGNING_LEASE.SINGLETON.isTrue())
        .returning(
            TRANSCODE_CA_SIGNING_LEASE.OPERATION,
            TRANSCODE_CA_SIGNING_LEASE.OWNER_ID,
            TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH,
            TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL)
        .fetchOptional(leaseRecord -> toLease(leaseRecord, databaseTime.toInstant()));
  }

  @Override
  public Optional<CertificateSigningLease> renew(
      CertificateSigningLease currentLease, Duration duration) {
    requirePositive(duration);
    return dsl.transactionResult(
        configuration -> renew(DSL.using(configuration), currentLease, duration));
  }

  private Optional<CertificateSigningLease> renew(
      DSLContext transaction, CertificateSigningLease currentLease, Duration duration) {
    var current =
        transaction
            .selectFrom(TRANSCODE_CA_SIGNING_LEASE)
            .where(CertificateSigningLeaseGuard.identity(currentLease))
            .forUpdate()
            .fetchOptional();
    if (current.isEmpty()) {
      return Optional.empty();
    }

    var databaseTime = CertificateSigningLeaseGuard.databaseTime(transaction);
    if (!current.orElseThrow().getLeaseUntil().isAfter(databaseTime)) {
      return Optional.empty();
    }

    var leaseUntil = databaseTime.plus(duration);
    return transaction
        .update(TRANSCODE_CA_SIGNING_LEASE)
        .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, leaseUntil)
        .where(CertificateSigningLeaseGuard.identity(currentLease))
        .returning(
            TRANSCODE_CA_SIGNING_LEASE.OPERATION,
            TRANSCODE_CA_SIGNING_LEASE.OWNER_ID,
            TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH,
            TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL)
        .fetchOptional(leaseRecord -> toLease(leaseRecord, databaseTime.toInstant()));
  }

  @Override
  public boolean isCurrent(CertificateSigningLease lease) {
    return CertificateSigningLeaseGuard.isCurrent(dsl, lease);
  }

  @Override
  public boolean release(CertificateSigningLease lease) {
    return dsl.update(TRANSCODE_CA_SIGNING_LEASE)
            .set(
                TRANSCODE_CA_SIGNING_LEASE.OPERATION,
                (com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation) null)
            .set(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID, (UUID) null)
            .set(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL, (OffsetDateTime) null)
            .where(CertificateSigningLeaseGuard.identity(lease))
            .execute()
        == 1;
  }

  private CertificateSigningLease toLease(
      TranscodeCaSigningLeaseRecord leaseRecord, java.time.Instant databaseTime) {
    var leaseUntil = leaseRecord.getLeaseUntil().toInstant();
    return CertificateSigningLease.builder()
        .operation(CertificateAuthorityOperation.valueOf(leaseRecord.getOperation().getLiteral()))
        .ownerId(leaseRecord.getOwnerId())
        .fencingEpoch(leaseRecord.getFencingEpoch())
        .databaseTime(databaseTime)
        .leaseUntil(leaseUntil)
        .build();
  }

  private com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation generated(
      CertificateAuthorityOperation operation) {
    return com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation.valueOf(
        operation.name());
  }

  private void requirePositive(Duration duration) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Certificate authority signing lease must be positive");
    }
    if (duration.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease must use microsecond precision");
    }
    if (duration.compareTo(MAXIMUM_LEASE_DURATION) > 0) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease must not exceed 5 minutes");
    }
  }
}
