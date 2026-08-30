package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class FakeDeviceRegistrationRepository extends FakeJpaRepository<DeviceRegistration>
    implements DeviceRegistrationRepository {

  @Override
  public List<DeviceRegistration> findPageByHouseholdIdAndStatus(
      UUID householdId, DeviceRegistrationStatus status, KeysetPaginationOptions options) {
    return FakeAuditableEntityPage.find(
        database.values(),
        registration ->
            householdId.equals(registration.getHouseholdId()) && registration.getStatus() == status,
        options);
  }

  @Override
  public List<DeviceRegistration> findByHouseholdIdAndStatus(
      UUID householdId, DeviceRegistrationStatus status) {
    return database.values().stream()
        .filter(registration -> householdId.equals(registration.getHouseholdId()))
        .filter(registration -> registration.getStatus() == status)
        .toList();
  }

  @Override
  public boolean tryRevoke(UUID registrationId, UUID actorAccountId, String reason, Instant now) {
    var registration = database.get(registrationId);
    if (registration == null || registration.getStatus() != DeviceRegistrationStatus.ACTIVE) {
      return false;
    }

    revoke(registration, actorAccountId, reason, now);
    return true;
  }

  @Override
  public List<UUID> revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now) {
    return revokeAll(
        registration ->
            esn.equals(registration.getEsn())
                && (householdId == null || householdId.equals(registration.getHouseholdId())),
        actorAccountId,
        reason,
        now);
  }

  @Override
  public List<UUID> revokeAllByAccountAndHousehold(
      UUID authorizingAccountId, UUID householdId, String reason, Instant now) {
    return revokeAll(
        registration ->
            authorizingAccountId.equals(registration.getAuthorizingAccountId())
                && householdId.equals(registration.getHouseholdId()),
        null,
        reason,
        now);
  }

  @Override
  public List<UUID> revokeAllByAccount(UUID authorizingAccountId, String reason, Instant now) {
    return revokeAll(
        registration -> authorizingAccountId.equals(registration.getAuthorizingAccountId()),
        null,
        reason,
        now);
  }

  private List<UUID> revokeAll(
      Predicate<DeviceRegistration> scope, UUID actorAccountId, String reason, Instant now) {
    var matching =
        database.values().stream()
            .filter(registration -> registration.getStatus() == DeviceRegistrationStatus.ACTIVE)
            .filter(scope)
            .toList();
    matching.forEach(registration -> revoke(registration, actorAccountId, reason, now));
    return matching.stream().map(DeviceRegistration::getId).toList();
  }

  private static void revoke(
      DeviceRegistration registration, UUID actorAccountId, String reason, Instant now) {
    registration.setStatus(DeviceRegistrationStatus.REVOKED);
    registration.setRevokedAt(now);
    registration.setRevokedByAccountId(actorAccountId);
    registration.setRevocationReason(reason);
  }
}
