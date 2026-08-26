package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptHistory;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.exceptions.CredentialAttemptNotPendingException;
import com.streamarr.server.jooq.generated.tables.records.CredentialAttemptRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credential attempt journal and its admission decisions (ADR 0028). Reservation and completion
 * each run in their own REQUIRES_NEW transaction, so a reservation is committed and visible to
 * every instance before the verifier runs and a completion survives the caller's exception path. A
 * transaction-scoped advisory lock keyed by the target serializes admissions across instances;
 * completion takes the same lock so no row can complete between the failure and pending reads of a
 * concurrent admission and be counted by neither.
 */
@Repository
@RequiredArgsConstructor
@SuppressWarnings("checkstyle:fullyQualifiedName")
public class JooqCredentialAttemptRepository implements CredentialAttemptRepository {

  /** ADR 0028: a reservation nobody completed stops consuming capacity after five minutes. */
  private static final Duration ABANDONED_RESERVATION_TIMEOUT = Duration.ofMinutes(5);

  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
  private static final String LOCK_NAMESPACE = "credential-attempt";

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    if (!(policy instanceof CredentialAttemptPolicy.Limited limited) || !target.isResolved()) {
      return insert(target, attemptedAt);
    }

    lockTarget(target);
    return limited
        .retryAfter(history(target, limited, attemptedAt), attemptedAt)
        .<CredentialAttemptAdmission>map(CredentialAttemptAdmission.Blocked::new)
        .orElseGet(() -> insert(target, attemptedAt));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(
      CredentialAttemptReservation reservation,
      CredentialAttemptResult result,
      Instant completedAt) {
    if (reservation.target().isResolved()) {
      lockTarget(reservation.target());
    }

    var completed =
        dsl.update(CREDENTIAL_ATTEMPT)
            .set(CREDENTIAL_ATTEMPT.COMPLETED_AT, offsetOf(completedAt))
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

  private CredentialAttemptAdmission insert(CredentialAttemptTarget target, Instant attemptedAt) {
    var id = UUID.randomUUID();
    var ipAddress = DSL.val(target.ipAddress()).cast(CREDENTIAL_ATTEMPT.IP_ADDRESS.getDataType());
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
                DSL.val(generatedKind(target)),
                DSL.val(target.accountId(), CREDENTIAL_ATTEMPT.ACCOUNT_ID.getDataType()),
                DSL.val(target.profileId(), CREDENTIAL_ATTEMPT.PROFILE_ID.getDataType()),
                DSL.val(target.credentialId(), CREDENTIAL_ATTEMPT.CREDENTIAL_ID.getDataType()),
                ipAddress,
                DSL.val(offsetOf(attemptedAt))))
        .execute();

    return new CredentialAttemptAdmission.Reserved(new CredentialAttemptReservation(id, target));
  }

  /**
   * The failures fetched reach back one window plus one throttle: that is the oldest failure that
   * can still anchor a lockout running at {@code now}.
   */
  private CredentialAttemptHistory history(
      CredentialAttemptTarget target, CredentialAttemptPolicy.Limited policy, Instant now) {
    var latestSuccess = latestSuccess(target);
    var earliestRelevant =
        offsetOf(now.minus(policy.failureWindow()).minus(policy.throttleDuration()));
    var failures =
        dsl
            .select(CREDENTIAL_ATTEMPT.COMPLETED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(targetCondition(target))
            .and(
                CREDENTIAL_ATTEMPT.RESULT.eq(
                    com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.FAILED))
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
            .where(targetCondition(target))
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

  private Optional<OffsetDateTime> latestSuccess(CredentialAttemptTarget target) {
    return Optional.ofNullable(
        dsl.select(DSL.max(CREDENTIAL_ATTEMPT.COMPLETED_AT))
            .from(CREDENTIAL_ATTEMPT)
            .where(targetCondition(target))
            // Stated explicitly: the partial index on completed rows is only provable from it.
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.isNotNull())
            .and(
                CREDENTIAL_ATTEMPT.RESULT.eq(
                    com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.SUCCEEDED))
            .fetchOne(DSL.max(CREDENTIAL_ATTEMPT.COMPLETED_AT)));
  }

  private static Condition after(
      Field<OffsetDateTime> column, Optional<OffsetDateTime> exclusiveBound) {
    return exclusiveBound.map(column::gt).orElseGet(DSL::noCondition);
  }

  private Condition targetCondition(CredentialAttemptTarget target) {
    return DSL.and(
        CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(generatedKind(target)),
        identifierCondition(CREDENTIAL_ATTEMPT.ACCOUNT_ID, target.accountId()),
        identifierCondition(CREDENTIAL_ATTEMPT.PROFILE_ID, target.profileId()),
        identifierCondition(CREDENTIAL_ATTEMPT.CREDENTIAL_ID, target.credentialId()));
  }

  /**
   * Renders {@code =} or {@code IS NULL}: PostgreSQL never uses {@code IS NOT DISTINCT FROM} as a
   * btree index condition, so the null-safe form would scan every row of the kind.
   */
  private static Condition identifierCondition(
      TableField<CredentialAttemptRecord, UUID> column, UUID id) {
    if (id == null) {
      return column.isNull();
    }

    return column.eq(id);
  }

  /**
   * The key names exactly the identifiers {@link #targetCondition} matches, so the lock covers the
   * read set of the admission it serializes; a hash collision only adds serialization.
   */
  private void lockTarget(CredentialAttemptTarget target) {
    var key =
        "%s:%s:%s:%s"
            .formatted(
                target.kind(), target.accountId(), target.profileId(), target.credentialId());
    transactionLocks.lockNormalizedKey(LOCK_NAMESPACE, key, LOCK_TIMEOUT);
  }

  private static com.streamarr.server.jooq.generated.enums.CredentialKind generatedKind(
      CredentialAttemptTarget target) {
    return switch (target.kind()) {
      case ACCOUNT_LOGIN -> com.streamarr.server.jooq.generated.enums.CredentialKind.ACCOUNT_LOGIN;
      case ACCOUNT_PASSWORD_VERIFICATION ->
          com.streamarr.server.jooq.generated.enums.CredentialKind.ACCOUNT_PASSWORD_VERIFICATION;
      case PROFILE_PIN -> com.streamarr.server.jooq.generated.enums.CredentialKind.PROFILE_PIN;
      case ACCOUNT_INVITATION_CODE ->
          com.streamarr.server.jooq.generated.enums.CredentialKind.ACCOUNT_INVITATION_CODE;
      case PASSWORD_RESET_CODE ->
          com.streamarr.server.jooq.generated.enums.CredentialKind.PASSWORD_RESET_CODE;
      case PROFILE_MANAGER_INVITATION_CODE ->
          com.streamarr.server.jooq.generated.enums.CredentialKind.PROFILE_MANAGER_INVITATION_CODE;
      case DEVICE_PAIRING_CODE ->
          com.streamarr.server.jooq.generated.enums.CredentialKind.DEVICE_PAIRING_CODE;
    };
  }

  private static com.streamarr.server.jooq.generated.enums.CredentialAttemptResult generatedResult(
      CredentialAttemptResult result) {
    return switch (result) {
      case FAILED -> com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.FAILED;
      case SUCCEEDED -> com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.SUCCEEDED;
    };
  }

  private static OffsetDateTime offsetOf(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
