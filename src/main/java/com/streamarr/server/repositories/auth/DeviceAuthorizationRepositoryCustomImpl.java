package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.DeviceAuthorization.DEVICE_AUTHORIZATION;

import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.jooq.generated.enums.DeviceAuthorizationStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class DeviceAuthorizationRepositoryCustomImpl
    implements DeviceAuthorizationRepositoryCustom {

  /** Arbitrary but fixed: only issuance takes this lock, so it contends with nothing else. */
  private static final long ISSUANCE_LOCK_KEY = 0x5354524D_44455601L;

  private static final String DEVICE_CODE_UNIQUE_CONSTRAINT =
      "uq_device_authorization_device_code_digest";
  private static final String USER_CODE_UNIQUE_CONSTRAINT = "uq_device_authorization_user_code";

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @PersistenceContext private final EntityManager entityManager;

  @Override
  public Optional<DeviceAuthorization> lockByDeviceCodeDigest(String deviceCodeDigest) {
    var query =
        dsl.selectFrom(DEVICE_AUTHORIZATION)
            .where(DEVICE_AUTHORIZATION.DEVICE_CODE_DIGEST.eq(deviceCodeDigest))
            .forUpdate();

    return JooqQueryHelper.nativeQuery(entityManager, query, DeviceAuthorization.class).stream()
        .findFirst();
  }

  @Override
  @Transactional
  public int decide(DeviceAuthorizationDecisionCommand command) {
    var nowOffset = offsetOf(command.now());

    return dsl.update(DEVICE_AUTHORIZATION)
        .set(DEVICE_AUTHORIZATION.STATUS, generatedStatusOf(command.status()))
        .set(DEVICE_AUTHORIZATION.DECIDED_BY_ACCOUNT_ID, command.decidedByAccountId())
        .set(DEVICE_AUTHORIZATION.DECIDED_AT, nowOffset)
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_ON, nowOffset)
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_BY, currentAuditor())
        .where(DEVICE_AUTHORIZATION.USER_CODE.eq(command.userCode()))
        .and(DEVICE_AUTHORIZATION.STATUS.eq(DeviceAuthorizationStatus.PENDING))
        .and(DEVICE_AUTHORIZATION.EXPIRES_AT.gt(nowOffset))
        .execute();
  }

  @Override
  @Transactional
  public void updateCadence(UUID id, int pollIntervalSeconds, Instant nextPollAt, Instant now) {
    var nowOffset = offsetOf(now);

    dsl.update(DEVICE_AUTHORIZATION)
        .set(DEVICE_AUTHORIZATION.POLL_INTERVAL_SECONDS, pollIntervalSeconds)
        .set(DEVICE_AUTHORIZATION.NEXT_POLL_AT, offsetOf(nextPollAt))
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_ON, nowOffset)
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_BY, currentAuditor())
        .where(DEVICE_AUTHORIZATION.ID.eq(id))
        .execute();
  }

  @Override
  @Transactional
  public void markConsumed(UUID id, Instant now) {
    var nowOffset = offsetOf(now);

    dsl.update(DEVICE_AUTHORIZATION)
        .set(DEVICE_AUTHORIZATION.STATUS, DeviceAuthorizationStatus.CONSUMED)
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_ON, nowOffset)
        .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_BY, currentAuditor())
        .where(DEVICE_AUTHORIZATION.ID.eq(id))
        .execute();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeviceAuthorizationInsertResult tryInsertWithinCap(
      DeviceAuthorizationInsertCommand command) {
    // Held until this transaction ends, and shared across instances because it lives in the
    // database. Every issuance passes through here, so the count below cannot go stale under it.
    dsl.execute("SELECT pg_advisory_xact_lock(?)", ISSUANCE_LOCK_KEY);

    var outstanding = countOutstanding(command.now());
    if (outstanding >= command.maxOutstanding()) {
      return new DeviceAuthorizationInsertResult(false, outstanding);
    }

    var nowOffset = offsetOf(command.now());
    try {
      dsl.insertInto(DEVICE_AUTHORIZATION)
          .set(DEVICE_AUTHORIZATION.DEVICE_CODE_DIGEST, command.deviceCodeDigest())
          .set(DEVICE_AUTHORIZATION.USER_CODE, command.userCode())
          .set(DEVICE_AUTHORIZATION.STATUS, DeviceAuthorizationStatus.PENDING)
          .set(DEVICE_AUTHORIZATION.DEVICE_NAME, command.deviceName())
          .set(DEVICE_AUTHORIZATION.EXPIRES_AT, offsetOf(command.expiresAt()))
          .set(DEVICE_AUTHORIZATION.NEXT_POLL_AT, offsetOf(command.nextPollAt()))
          .set(DEVICE_AUTHORIZATION.POLL_INTERVAL_SECONDS, command.pollIntervalSeconds())
          .set(DEVICE_AUTHORIZATION.CREATED_ON, nowOffset)
          .set(DEVICE_AUTHORIZATION.CREATED_BY, currentAuditor())
          .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_ON, nowOffset)
          .set(DEVICE_AUTHORIZATION.LAST_MODIFIED_BY, currentAuditor())
          .execute();
    } catch (DuplicateKeyException e) {
      if (isDeviceCodeCollision(e)) {
        throw new DeviceCodeCollisionException(e);
      }
      if (isUserCodeCollision(e)) {
        throw new UserCodeCollisionException(e);
      }
      throw e;
    }

    return new DeviceAuthorizationInsertResult(true, outstanding + 1);
  }

  @Override
  public int countOutstanding(Instant now) {
    return dsl.fetchCount(
        DEVICE_AUTHORIZATION,
        DEVICE_AUTHORIZATION
            .STATUS
            .eq(DeviceAuthorizationStatus.PENDING)
            .and(DEVICE_AUTHORIZATION.EXPIRES_AT.gt(offsetOf(now))));
  }

  @Override
  public Optional<Instant> findOldestOutstandingExpiry(Instant now) {
    var oldestExpiry = DSL.min(DEVICE_AUTHORIZATION.EXPIRES_AT);

    return dsl.select(oldestExpiry)
        .from(DEVICE_AUTHORIZATION)
        .where(DEVICE_AUTHORIZATION.STATUS.eq(DeviceAuthorizationStatus.PENDING))
        .and(DEVICE_AUTHORIZATION.EXPIRES_AT.gt(offsetOf(now)))
        .fetchOptional(oldestExpiry)
        .map(OffsetDateTime::toInstant);
  }

  @Override
  @Transactional
  public int deleteExpired(Instant cutoff) {
    return dsl.deleteFrom(DEVICE_AUTHORIZATION)
        .where(DEVICE_AUTHORIZATION.EXPIRES_AT.le(offsetOf(cutoff)))
        .execute();
  }

  @Override
  public Optional<com.streamarr.server.domain.auth.DeviceAuthorizationStatus> findStatusByUserCode(
      String userCode) {
    return dsl.select(DEVICE_AUTHORIZATION.STATUS)
        .from(DEVICE_AUTHORIZATION)
        .where(DEVICE_AUTHORIZATION.USER_CODE.eq(userCode))
        .fetchOptional(DEVICE_AUTHORIZATION.STATUS)
        .map(DeviceAuthorizationRepositoryCustomImpl::domainStatusOf);
  }

  private UUID currentAuditor() {
    return auditorAware.getCurrentAuditor().orElse(null);
  }

  private static boolean isUserCodeCollision(DuplicateKeyException exception) {
    return hasConstraint(exception, USER_CODE_UNIQUE_CONSTRAINT);
  }

  private static boolean isDeviceCodeCollision(DuplicateKeyException exception) {
    return hasConstraint(exception, DEVICE_CODE_UNIQUE_CONSTRAINT);
  }

  private static boolean hasConstraint(DuplicateKeyException exception, String constraintName) {
    if (!(exception.getMostSpecificCause() instanceof PSQLException postgresException)) {
      return false;
    }

    var serverError = postgresException.getServerErrorMessage();
    return serverError != null && constraintName.equals(serverError.getConstraint());
  }

  private static DeviceAuthorizationStatus generatedStatusOf(
      com.streamarr.server.domain.auth.DeviceAuthorizationStatus status) {
    return DeviceAuthorizationStatus.valueOf(status.name());
  }

  private static com.streamarr.server.domain.auth.DeviceAuthorizationStatus domainStatusOf(
      DeviceAuthorizationStatus status) {
    return com.streamarr.server.domain.auth.DeviceAuthorizationStatus.valueOf(status.name());
  }

  private static OffsetDateTime offsetOf(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
