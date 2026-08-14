package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  ProfileHouseholdShare insertPendingIfAbsent(UUID profileId, UUID householdId);
}
