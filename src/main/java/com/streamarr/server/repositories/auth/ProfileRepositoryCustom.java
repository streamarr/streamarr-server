package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Profile;
import java.util.List;
import java.util.UUID;

public interface ProfileRepositoryCustom {

  /** Profiles actively shared into the Household, ordered by name then id for stable paging. */
  List<Profile> findAvailableInHousehold(UUID householdId);
}
