package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeviceRegistrationRepositoryCustom {

  /** Revokes one ACTIVE registration; false when already revoked or missing. */
  boolean tryRevoke(UUID registrationId, UUID actorAccountId, String reason, Instant now);

  /**
   * Revokes every ACTIVE registration in the scope — an ESN (optionally Household-scoped), one
   * authorizing Account's Household, or a whole Household — returning the revoked ids so the caller
   * can drop their sessions in the same transaction.
   */
  List<UUID> revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now);

  List<UUID> revokeAllByAccountAndHousehold(
      UUID authorizingAccountId, UUID householdId, String reason, Instant now);

  List<UUID> revokeAllByAccount(UUID authorizingAccountId, String reason, Instant now);
}
