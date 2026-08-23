package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  /** Whether the Profile is available in the Household right now, read as a scalar. */
  boolean isActivelyShared(UUID profileId, UUID householdId);
}
