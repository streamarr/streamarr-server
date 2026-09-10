package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.FAILED;
import static com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.SUCCEEDED;
import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptHistory;
import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.exceptions.CredentialAttemptNotPendingException;
import com.streamarr.server.jooq.generated.enums.CredentialKind;
import com.streamarr.server.jooq.generated.tables.records.CredentialAttemptRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits reservations and outcomes independently of the caller's transaction. */
@Repository
@RequiredArgsConstructor
public class JooqCredentialAttemptRepository implements CredentialAttemptRepository {

  private static final Duration ABANDONED_RESERVATION_TIMEOUT = Duration.ofMinutes(5);

  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
  private static final String LOCK_NAMESPACE = "credential-attempt";

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;
  private final CredentialAttemptClock clock;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CredentialAttemptAdmission reserve(
      CredentialAttemptMetadata metadata, CredentialAttemptPolicy policy) {
    if (!(policy instanceof CredentialAttemptPolicy.Limited limited) || !metadata.isResolved()) {
      transactionLocks.limitLockWait(LOCK_TIMEOUT);
      return insert(metadata, clock.instant());
    }

    lockTarget(metadata);
    var attemptedAt = clock.instant();
    return limited
        .retryAfter(history(metadata, limited, attemptedAt), attemptedAt)
        .<CredentialAttemptAdmission>map(CredentialAttemptAdmission.Blocked::new)
        .orElseGet(() -> insert(metadata, attemptedAt));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(CredentialAttemptReservation reservation, CredentialAttemptResult result) {
    lockTargetOrLimitWait(reservation.metadata());
    completeLocked(reservation, result);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T completeWith(CredentialAttemptReservation reservation, Supplier<T> mutation) {
    lockTargetOrLimitWait(reservation.metadata());
    var result = mutation.get();
    completeLocked(reservation, CredentialAttemptResult.SUCCEEDED);
    return result;
  }

  private void completeLocked(
      CredentialAttemptReservation reservation, CredentialAttemptResult result) {
    var completedAt = clock.instant();

    var completed =
        dsl.update(CREDENTIAL_ATTEMPT)
            // Keep completed_at >= attempted_at if the database clock moves backward.
            .set(
                CREDENTIAL_ATTEMPT.COMPLETED_AT,
                DSL.greatest(CREDENTIAL_ATTEMPT.ATTEMPTED_AT, DSL.val(offsetOf(completedAt))))
            .set(CREDENTIAL_ATTEMPT.RESULT, generatedResult(result))
            .where(CREDENTIAL_ATTEMPT.ID.eq(reservation.id()))
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.isNull())
            .execute();
    if (completed != 1) {
      throw new CredentialAttemptNotPendingException();
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int deleteAttemptedBefore(Instant cutoff) {
    return dsl.deleteFrom(CREDENTIAL_ATTEMPT)
        .where(CREDENTIAL_ATTEMPT.ATTEMPTED_AT.lt(offsetOf(cutoff)))
        .execute();
  }

  private CredentialAttemptAdmission insert(
      CredentialAttemptMetadata metadata, Instant attemptedAt) {
    var id = UUID.randomUUID();
    var ipAddress = DSL.val(metadata.ipAddress()).cast(CREDENTIAL_ATTEMPT.IP_ADDRESS.getDataType());
    dsl.insertInto(
            CREDENTIAL_ATTEMPT,
            CREDENTIAL_ATTEMPT.ID,
            CREDENTIAL_ATTEMPT.CREDENTIAL_KIND,
            CREDENTIAL_ATTEMPT.ACCOUNT_ID,
            CREDENTIAL_ATTEMPT.PROFILE_ID,
            CREDENTIAL_ATTEMPT.CREDENTIAL_ID,
            CREDENTIAL_ATTEMPT.IP_ADDRESS,
            CREDENTIAL_ATTEMPT.ATTEMPTED_AT)
        .select(
            dsl.select(
                DSL.val(id),
                DSL.val(generatedKind(metadata)),
                DSL.val(metadata.accountId(), CREDENTIAL_ATTEMPT.ACCOUNT_ID.getDataType()),
                DSL.val(metadata.profileId(), CREDENTIAL_ATTEMPT.PROFILE_ID.getDataType()),
                DSL.val(metadata.credentialId(), CREDENTIAL_ATTEMPT.CREDENTIAL_ID.getDataType()),
                ipAddress,
                DSL.val(offsetOf(attemptedAt))))
        .execute();

    return new CredentialAttemptAdmission.Reserved(new CredentialAttemptReservation(id, metadata));
  }

  private CredentialAttemptHistory history(
      CredentialAttemptMetadata metadata, CredentialAttemptPolicy.Limited policy, Instant now) {
    var latestSuccess =
        policy.resetFailuresOnSuccess()
            ? latestSuccess(metadata)
            : Optional.<OffsetDateTime>empty();
    // A lockout can outlast its failure window, so read back through both durations.
    var earliestRelevant =
        offsetOf(now.minus(policy.failureWindow()).minus(policy.throttleDuration()));
    var failures =
        dsl
            .select(CREDENTIAL_ATTEMPT.COMPLETED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(targetCondition(metadata))
            .and(CREDENTIAL_ATTEMPT.RESULT.eq(FAILED))
            .and(after(CREDENTIAL_ATTEMPT.COMPLETED_AT, latestSuccess))
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.ge(earliestRelevant))
            .orderBy(CREDENTIAL_ATTEMPT.COMPLETED_AT.asc())
            .fetch(CREDENTIAL_ATTEMPT.COMPLETED_AT)
            .stream()
            .map(OffsetDateTime::toInstant)
            .toList();
    var pendingExpiries =
        dsl
            .select(CREDENTIAL_ATTEMPT.ATTEMPTED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(targetCondition(metadata))
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.isNull())
            .and(
                CREDENTIAL_ATTEMPT.ATTEMPTED_AT.gt(
                    offsetOf(now.minus(ABANDONED_RESERVATION_TIMEOUT))))
            .fetch(CREDENTIAL_ATTEMPT.ATTEMPTED_AT)
            .stream()
            .map(attemptedAt -> attemptedAt.toInstant().plus(ABANDONED_RESERVATION_TIMEOUT))
            .toList();
    return new CredentialAttemptHistory(failures, pendingExpiries);
  }

  private Optional<OffsetDateTime> latestSuccess(CredentialAttemptMetadata metadata) {
    return Optional.ofNullable(
        dsl.select(DSL.max(CREDENTIAL_ATTEMPT.COMPLETED_AT))
            .from(CREDENTIAL_ATTEMPT)
            .where(targetCondition(metadata))
            // Required for PostgreSQL to use the partial index on completed attempts.
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.isNotNull())
            .and(CREDENTIAL_ATTEMPT.RESULT.eq(SUCCEEDED))
            .fetchOne(DSL.max(CREDENTIAL_ATTEMPT.COMPLETED_AT)));
  }

  private static Condition after(
      Field<OffsetDateTime> column, Optional<OffsetDateTime> exclusiveBound) {
    return exclusiveBound.map(column::gt).orElseGet(DSL::noCondition);
  }

  private Condition targetCondition(CredentialAttemptMetadata metadata) {
    return DSL.and(
        CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(generatedKind(metadata)),
        identifierCondition(CREDENTIAL_ATTEMPT.ACCOUNT_ID, metadata.accountId()),
        identifierCondition(CREDENTIAL_ATTEMPT.PROFILE_ID, metadata.profileId()),
        identifierCondition(CREDENTIAL_ATTEMPT.CREDENTIAL_ID, metadata.credentialId()));
  }

  private static Condition identifierCondition(
      TableField<CredentialAttemptRecord, UUID> column, UUID id) {
    // Use = and IS NULL so PostgreSQL can use the target indexes.
    if (id == null) {
      return column.isNull();
    }

    return column.eq(id);
  }

  private void lockTargetOrLimitWait(CredentialAttemptMetadata metadata) {
    // Unresolved targets skip the advisory lock but still need a lock timeout for journal writes.
    if (!metadata.isResolved()) {
      transactionLocks.limitLockWait(LOCK_TIMEOUT);
      return;
    }

    lockTarget(metadata);
  }

  private void lockTarget(CredentialAttemptMetadata metadata) {
    // Reservation and completion share this lock to keep history's two reads consistent.
    // Use the same identifiers as targetCondition.
    var key =
        "%s:%s:%s:%s"
            .formatted(
                metadata.kind(),
                metadata.accountId(),
                metadata.profileId(),
                metadata.credentialId());
    transactionLocks.lockNormalizedKey(LOCK_NAMESPACE, key, LOCK_TIMEOUT);
  }

  private static CredentialKind generatedKind(CredentialAttemptMetadata metadata) {
    return switch (metadata.kind()) {
      case ACCOUNT_LOGIN -> CredentialKind.ACCOUNT_LOGIN;
      case ACCOUNT_PASSWORD_VERIFICATION -> CredentialKind.ACCOUNT_PASSWORD_VERIFICATION;
      case PROFILE_PIN -> CredentialKind.PROFILE_PIN;
      case ACCOUNT_INVITATION_CODE -> CredentialKind.ACCOUNT_INVITATION_CODE;
      case PASSWORD_RESET_CODE -> CredentialKind.PASSWORD_RESET_CODE;
      case PROFILE_MANAGER_INVITATION_CODE -> CredentialKind.PROFILE_MANAGER_INVITATION_CODE;
      case DEVICE_PAIRING_CODE -> CredentialKind.DEVICE_PAIRING_CODE;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.CredentialAttemptResult generatedResult(
      CredentialAttemptResult result) {
    return switch (result) {
      case FAILED -> FAILED;
      case SUCCEEDED -> SUCCEEDED;
    };
  }

  private static OffsetDateTime offsetOf(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
