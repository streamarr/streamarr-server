package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeviceRegistrationRepositoryCustom {

  List<DeviceRegistration> findPageByHouseholdIdAndStatus(
      UUID householdId, DeviceRegistrationStatus status, KeysetPaginationOptions options);

  /** Revokes one ACTIVE registration; false when already revoked or missing. */
  boolean tryRevoke(UUID registrationId, UUID actorAccountId, String reason, Instant now);

  /**
   * Revokes every ACTIVE registration in the scope — an ESN (optionally Household-scoped), one
   * authorizing Account in one Household, or every registration of one authorizing Account —
   * returning the revoked ids so the caller can drop their sessions in the same transaction.
   */
  List<UUID> revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now);

  List<UUID> revokeAllByAccountAndHousehold(
      UUID authorizingAccountId, UUID householdId, String reason, Instant now);

  List<UUID> revokeAllByAccount(UUID authorizingAccountId, String reason, Instant now);

  /** Revokes every ACTIVE registration bound to a deleted Household. */
  List<UUID> revokeAllByHousehold(UUID householdId, String reason, Instant now);
}
