package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.DeviceRegistration.DEVICE_REGISTRATION;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

// checkstyle:fullyQualifiedName suppressed for the class: the domain and generated jOOQ status
// enums share a simple name, an unavoidable collision.
@SuppressWarnings("checkstyle:fullyQualifiedName")
@RequiredArgsConstructor
public class DeviceRegistrationRepositoryCustomImpl implements DeviceRegistrationRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean tryRevoke(UUID registrationId, UUID actorAccountId, String reason, Instant now) {
    return !revokeWhere(DEVICE_REGISTRATION.ID.eq(registrationId), actorAccountId, reason, now)
        .isEmpty();
  }

  @Override
  public List<UUID> revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now) {
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

  private List<UUID> revokeWhere(Condition scope, UUID actorAccountId, String reason, Instant now) {
    return dsl.update(DEVICE_REGISTRATION)
        .set(
            DEVICE_REGISTRATION.STATUS,
            com.streamarr.server.jooq.generated.enums.DeviceRegistrationStatus.REVOKED)
        .set(DEVICE_REGISTRATION.REVOKED_AT, now.atOffset(ZoneOffset.UTC))
        .set(DEVICE_REGISTRATION.REVOKED_BY_ACCOUNT_ID, actorAccountId)
        .set(DEVICE_REGISTRATION.REVOCATION_REASON, reason)
        .set(DEVICE_REGISTRATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(DEVICE_REGISTRATION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(
            DEVICE_REGISTRATION.STATUS.eq(
                com.streamarr.server.jooq.generated.enums.DeviceRegistrationStatus.ACTIVE))
        .returning(DEVICE_REGISTRATION.ID)
        .fetch(DEVICE_REGISTRATION.ID);
  }
}
