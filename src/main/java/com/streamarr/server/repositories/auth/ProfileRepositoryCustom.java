package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepositoryCustom {

  /** Profiles actively shared into the Household, ordered by name then id for stable paging. */
  List<Profile> findAvailableInHousehold(UUID householdId);

  /**
   * The Profile's current policy read under a row-level write lock (SELECT … FOR UPDATE), as
   * scalars: the transition classification must see the state the transaction will change, and a
   * managed JPA copy could be stale.
   */
  Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId);

  /** Conditionally writes the authorized transition; false when the row changed or vanished. */
  boolean tryApplyPolicy(
      UUID profileId, ProfilePolicySnapshot expected, ProfilePolicyTarget target);
}
