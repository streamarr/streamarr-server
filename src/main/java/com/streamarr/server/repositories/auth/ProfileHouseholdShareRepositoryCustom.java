package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.Optional;
import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  ProfileHouseholdShareInsertResult insertPendingIfAbsent(UUID profileId, UUID householdId);

  Optional<ProfileHouseholdShare> activatePending(UUID shareId);

  Optional<ProfileHouseholdShare> deletePending(UUID shareId);
}
