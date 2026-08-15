package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  /**
 * Inserts a pending household share when one does not already exist.
 *
 * @return the result of the insertion attempt
 */
ProfileHouseholdShareInsertResult insertPendingIfAbsent(UUID profileId, UUID householdId);
}
