package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.DeviceRegistrationStatus.ACTIVE;
import static com.streamarr.server.jooq.generated.enums.DeviceRegistrationStatus.REVOKED;
import static com.streamarr.server.jooq.generated.enums.DeviceRegistrationStatus.valueOf;
import static com.streamarr.server.jooq.generated.tables.DeviceRegistration.DEVICE_REGISTRATION;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class DeviceRegistrationRepositoryCustomImpl implements DeviceRegistrationRepositoryCustom {

  private static final int ESN_LOCK_NAMESPACE = 0x5354524D;

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;
  private final EntityManager entityManager;

  @Override
  public List<Row<DeviceRegistration>> rowsInHousehold(
      UUID householdId, DeviceRegistrationStatus status, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(DEVICE_REGISTRATION.CREATED_ON)
            .itemId(DEVICE_REGISTRATION.ID)
            .build();
    var query =
        keyset.rowsBeyond(
            keyset.selectFrom(dsl, DEVICE_REGISTRATION),
            DEVICE_REGISTRATION
                .HOUSEHOLD_ID
                .eq(householdId)
                .and(DEVICE_REGISTRATION.STATUS.eq(inline(valueOf(status.name())))),
            seek);
    return JooqQueryHelper.nativeRows(entityManager, query, DeviceRegistration.class, keyset);
  }

  @Override
  public boolean tryRevoke(UUID registrationId, UUID actorAccountId, String reason, Instant now) {
    return !revokeWhere(DEVICE_REGISTRATION.ID.eq(registrationId), actorAccountId, reason, now)
        .isEmpty();
  }

  @Override
  public List<UUID> revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now) {
    // The caller's transaction holds this cross-instance lock through its subsequent insert or
    // block write. Without it, two pairings can both observe no active registration and race the
    // partial unique index after this revoke returns.
    transactionLocks.lock(ESN_LOCK_NAMESPACE, esn.hashCode());
    var scope = DEVICE_REGISTRATION.ESN.eq(esn);
    if (householdId != null) {
      scope = scope.and(DEVICE_REGISTRATION.HOUSEHOLD_ID.eq(householdId));
    }

    return revokeWhere(scope, actorAccountId, reason, now);
  }

  @Override
  public List<UUID> revokeAllByAccountAndHousehold(
      UUID authorizingAccountId, UUID householdId, String reason, Instant now) {
    return revokeWhere(
        DEVICE_REGISTRATION
            .AUTHORIZING_ACCOUNT_ID
            .eq(authorizingAccountId)
            .and(DEVICE_REGISTRATION.HOUSEHOLD_ID.eq(householdId)),
        null,
        reason,
        now);
  }

  @Override
  public List<UUID> revokeAllByAccount(UUID authorizingAccountId, String reason, Instant now) {
    return revokeWhere(
        DEVICE_REGISTRATION.AUTHORIZING_ACCOUNT_ID.eq(authorizingAccountId), null, reason, now);
  }

  @Override
  public List<UUID> revokeAllByHousehold(UUID householdId, String reason, Instant now) {
    return revokeWhere(DEVICE_REGISTRATION.HOUSEHOLD_ID.eq(householdId), null, reason, now);
  }

  private List<UUID> revokeWhere(Condition scope, UUID actorAccountId, String reason, Instant now) {
    return dsl.update(DEVICE_REGISTRATION)
        .set(DEVICE_REGISTRATION.STATUS, REVOKED)
        .set(DEVICE_REGISTRATION.REVOKED_AT, now.atOffset(ZoneOffset.UTC))
        .set(DEVICE_REGISTRATION.REVOKED_BY_ACCOUNT_ID, actorAccountId)
        .set(DEVICE_REGISTRATION.REVOCATION_REASON, reason)
        .where(scope)
        .and(DEVICE_REGISTRATION.STATUS.eq(ACTIVE))
        .returning(DEVICE_REGISTRATION.ID)
        .fetch(DEVICE_REGISTRATION.ID);
  }
}
