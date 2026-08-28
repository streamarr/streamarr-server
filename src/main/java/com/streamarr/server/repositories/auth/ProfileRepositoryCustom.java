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

  boolean existsAvailableInHouseholdWithNameIgnoreCase(UUID householdId, String name);

  /**
   * The Profile's current policy read under a row-level write lock (SELECT … FOR UPDATE), as
   * scalars: the transition classification must see the state the transaction will change, and a
   * managed JPA copy could be stale.
   */
  Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId);

  /** Locks and confirms the Profile is unrestricted for a Share sovereignty decision. */
  boolean lockIfUnrestricted(UUID profileId);

  /** Locks the Profile row so relationship writes and permanent deletion have one winner. */
  boolean lockById(UUID profileId);

  /**
   * Locks the Profile's availability across its home Household and every active visit, acquiring
   * locks in PostgreSQL UUID order. The caller holds the Profile row lock, so this Household set
   * cannot change before commit.
   */
  void lockProfileAvailabilityAcrossHouseholds(UUID profileId);

  /** Locks the Profile row for a share without loading the share into Hibernate's cache. */
  boolean lockByShareId(UUID shareId);

  /**
   * Shares a Profile row lock with other relationship terminators while serializing against
   * invitation issuance and permanent deletion.
   */
  boolean lockSharedByShareId(UUID shareId);

  /**
   * Writes the authorized transition. The caller holds the row lock {@link #lockPolicyById} took in
   * this transaction, so the state the decision classified cannot have moved; false only when the
   * row vanished.
   */
  boolean tryApplyPolicy(UUID profileId, ProfilePolicyTarget target);

  /** Renames only; the deferred name-uniqueness trigger judges the result at commit. */
  boolean tryRename(UUID profileId, String name);

  boolean trySetPicture(UUID profileId, String picture);

  /** Writes the PIN hash (null removes it); the database refuses a blank hash. */
  boolean trySetPinHash(UUID profileId, String pinHash);

  /**
   * The row re-read from the database, not from Hibernate's first-level cache: a policy decision
   * inside this transaction already JPA-loaded the row, and after the jOOQ write the managed copy
   * is stale (the hybrid footgun).
   */
  Optional<Profile> findRefreshedById(UUID profileId);
}
