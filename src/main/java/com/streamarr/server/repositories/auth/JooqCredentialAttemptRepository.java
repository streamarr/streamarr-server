package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;

import com.streamarr.server.domain.auth.CredentialAttemptAdmission;
import com.streamarr.server.domain.auth.CredentialAttemptPolicy;
import com.streamarr.server.domain.auth.CredentialAttemptReservation;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.jooq.generated.tables.records.CredentialAttemptRecord;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@SuppressWarnings("checkstyle:fullyQualifiedName")
public class JooqCredentialAttemptRepository implements CredentialAttemptRepository {

  private static final Duration ABANDONED_RESERVATION_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);

  private final DSLContext dsl;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CredentialAttemptAdmission reserve(
      CredentialAttemptTarget target, CredentialAttemptPolicy policy, Instant attemptedAt) {
    if (policy instanceof CredentialAttemptPolicy.Limited limited && target.isResolved()) {
      lockTarget(target);
      var admission = admissionFor(target, limited, attemptedAt);
      if (admission.isPresent()) {
        return admission.orElseThrow();
      }
    }

    return insert(target, attemptedAt);
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
      throw new IllegalStateException("Credential attempt reservation is not pending");
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int deleteAttemptedBefore(Instant cutoff) {
    return dsl.deleteFrom(CREDENTIAL_ATTEMPT)
        .where(CREDENTIAL_ATTEMPT.ATTEMPTED_AT.lt(offsetOf(cutoff)))
        .execute();
  }

  private Optional<CredentialAttemptAdmission> admissionFor(
      CredentialAttemptTarget target, CredentialAttemptPolicy.Limited policy, Instant attemptedAt) {
    var now = offsetOf(attemptedAt);
    var latestSuccess = latestSuccess(target);
    var lockout = activeLockout(target, policy, latestSuccess, now);
    if (lockout.isPresent()) {
      return Optional.of(
          new CredentialAttemptAdmission.Blocked(Duration.between(now, lockout.orElseThrow())));
    }

    var recent = recentAttempts(target, policy, latestSuccess, now);
    if (recent.size() < policy.maximumFailures()) {
      return Optional.empty();
    }

    return Optional.of(new CredentialAttemptAdmission.Blocked(recent.retryAfter(now, policy)));
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

  private Optional<OffsetDateTime> activeLockout(
      CredentialAttemptTarget target,
      CredentialAttemptPolicy.Limited policy,
      Optional<OffsetDateTime> latestSuccess,
      OffsetDateTime now) {
    var earliestRelevant = now.minus(policy.failureWindow()).minus(policy.throttleDuration());
    var condition =
        completedFailureCondition(target, latestSuccess)
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.ge(earliestRelevant));
    var failures =
        dsl.select(CREDENTIAL_ATTEMPT.COMPLETED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(condition)
            .orderBy(CREDENTIAL_ATTEMPT.COMPLETED_AT.asc())
            .fetch(CREDENTIAL_ATTEMPT.COMPLETED_AT);

    for (var last = failures.size() - 1; last >= policy.maximumFailures() - 1; last--) {
      var first = failures.get(last - policy.maximumFailures() + 1);
      var threshold = failures.get(last);
      if (Duration.between(first, threshold).compareTo(policy.failureWindow()) > 0) {
        continue;
      }

      var lockoutEnds = threshold.plus(policy.throttleDuration());
      if (lockoutEnds.isAfter(now)) {
        return Optional.of(lockoutEnds);
      }
    }

    return Optional.empty();
  }

  private RecentAttempts recentAttempts(
      CredentialAttemptTarget target,
      CredentialAttemptPolicy.Limited policy,
      Optional<OffsetDateTime> latestSuccess,
      OffsetDateTime now) {
    var failures =
        dsl.select(CREDENTIAL_ATTEMPT.COMPLETED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(completedFailureCondition(target, latestSuccess))
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.ge(now.minus(policy.failureWindow())))
            .fetch(CREDENTIAL_ATTEMPT.COMPLETED_AT);
    var pendingCondition =
        targetCondition(target)
            .and(CREDENTIAL_ATTEMPT.COMPLETED_AT.isNull())
            .and(CREDENTIAL_ATTEMPT.ATTEMPTED_AT.gt(now.minus(ABANDONED_RESERVATION_TIMEOUT)));
    if (latestSuccess.isPresent()) {
      pendingCondition =
          pendingCondition.and(CREDENTIAL_ATTEMPT.ATTEMPTED_AT.gt(latestSuccess.orElseThrow()));
    }
    var pending =
        dsl.select(CREDENTIAL_ATTEMPT.ATTEMPTED_AT)
            .from(CREDENTIAL_ATTEMPT)
            .where(pendingCondition)
            .fetch(CREDENTIAL_ATTEMPT.ATTEMPTED_AT);

    return new RecentAttempts(failures, pending);
  }

  private Condition completedFailureCondition(
      CredentialAttemptTarget target, Optional<OffsetDateTime> latestSuccess) {
    var condition =
        targetCondition(target)
            .and(
                CREDENTIAL_ATTEMPT.RESULT.eq(
                    com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.FAILED));
    if (latestSuccess.isPresent()) {
      return condition.and(CREDENTIAL_ATTEMPT.COMPLETED_AT.gt(latestSuccess.orElseThrow()));
    }

    return condition;
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

  private void lockTarget(CredentialAttemptTarget target) {
    var key =
        "%s:%s:%s:%s"
            .formatted(
                target.kind(), target.accountId(), target.profileId(), target.credentialId());
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, DSL.val(key), DSL.inline(0L));
    var lock = DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keyHash);
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(LOCK_TIMEOUT.toMillis() + "ms")).execute();
    dsl.select(lock).execute();
  }

  private static com.streamarr.server.jooq.generated.enums.CredentialKind generatedKind(
      CredentialAttemptTarget target) {
    return com.streamarr.server.jooq.generated.enums.CredentialKind.valueOf(target.kind().name());
  }

  private static com.streamarr.server.jooq.generated.enums.CredentialAttemptResult generatedResult(
      CredentialAttemptResult result) {
    return com.streamarr.server.jooq.generated.enums.CredentialAttemptResult.valueOf(result.name());
  }

  private static OffsetDateTime offsetOf(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private record RecentAttempts(List<OffsetDateTime> failures, List<OffsetDateTime> pending) {

    int size() {
      return failures.size() + pending.size();
    }

    Duration retryAfter(OffsetDateTime now, CredentialAttemptPolicy.Limited policy) {
      var failureExpiry = failures.stream().map(failure -> failure.plus(policy.failureWindow()));
      var pendingExpiry =
          pending.stream().map(attempt -> attempt.plus(ABANDONED_RESERVATION_TIMEOUT));
      var availableAt =
          java.util.stream.Stream.concat(failureExpiry, pendingExpiry)
              .min(OffsetDateTime::compareTo)
              .orElseThrow();
      return Duration.between(now, availableAt);
    }
  }
}
