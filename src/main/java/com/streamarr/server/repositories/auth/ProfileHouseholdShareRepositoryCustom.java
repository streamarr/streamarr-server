package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  ProfileHouseholdShareInsertResult insertPendingIfAbsent(UUID profileId, UUID householdId);
}
