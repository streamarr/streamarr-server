package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdProfilesFilter;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProfileRepositoryCustom {

  /** Profiles actively shared into the Household, ordered by name then id for stable paging. */
  List<Profile> findAvailableInHousehold(UUID householdId);

  /**
   * The Profiles actively shared into the Household beyond the seek, ordered by lowercased name,
   * then ID.
   */
  List<Row<Profile>> rowsAvailableInHousehold(UUID householdId, Seek seek);

  /**
   * The Profiles the Account manages beyond the seek, in every Household, ordered by lowercased
   * name, then ID: its own Personal Profile while that is an unrestricted Adult, and every Profile
   * it holds a direct grant for.
   */
  List<Row<Profile>> rowsManagedBy(UUID accountId, Seek seek);

  /**
   * The Profiles the filter keeps for Household settings beyond the seek, ordered by lowercased
   * name, then ID.
   */
  List<Row<Profile>> rowsInHousehold(HouseholdProfilesFilter filter, Seek seek);

  /** Which of the Profiles are available in the Household. */
  Set<UUID> availableAmong(UUID householdId, Collection<UUID> profileIds);

  /** Which of the Profiles are available in the Household each of them belongs to. */
  Set<UUID> availableWhereTheyBelong(Collection<UUID> profileIds);

  /** The Kid Profiles actively shared into the Household, which decide its PIN safety rule. */
  List<Profile> findAvailableKidsInHousehold(UUID householdId);

  boolean existsAvailableInHouseholdWithNameIgnoreCase(UUID householdId, String name);

  /**
   * What the Account is to each of the Profiles that exist, in one statement: whether it manages
   * each and whether it supervises each, as the Cedar facts would decide them one at a time, and
   * whether each belongs to the Account's own Household.
   */
  List<ProfileReaderRelation> relationsTo(UUID accountId, Collection<UUID> profileIds);

  /**
   * The Profile's current policy read under a row-level write lock (SELECT … FOR UPDATE), as
   * scalars: the transition classification must see the state the transaction will change, and a
   * managed JPA copy could be stale.
   */
  Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId);

  /**
   * Locks the Profile and, when an Account has the address, that Account's Personal Profile, in
   * their id order and in one statement whatever the address, so two invitations that each name the
   * other's inviter wait for each other rather than deadlock (ADR 0024: one global lock order).
   * Whether the Profile exists.
   */
  boolean lockForInvitationTo(UUID profileId, String email);

  /**
   * Locks the Profile and the Account's Personal Profile in the order {@link #lockForInvitationTo}
   * takes them, so an acceptance that rechecks its inviter and an invite to that inviter's address
   * wait for each other rather than deadlock. Whether the Profile exists.
   */
  boolean lockWithPersonalProfileOf(UUID profileId, UUID accountId);

  /** Locks and confirms the Profile is unrestricted for a Share sovereignty decision. */
  boolean lockIfUnrestricted(UUID profileId);

  /** Locks the Profile row so relationship writes and permanent deletion have one winner. */
  boolean lockById(UUID profileId);

  /**
   * The Profile's name as it is stored now, read as a scalar so a managed copy loaded earlier in
   * the transaction cannot answer instead. Empty when the Profile does not exist.
   */
  Optional<String> findNameById(UUID profileId);

  /**
   * Shares the Profile's availability guards across its home Household and every active visit,
   * acquiring locks in PostgreSQL UUID order. The caller holds the Profile row lock, so this
   * Household set cannot change before commit.
   */
  void lockProfileAvailabilityAcrossHouseholds(UUID profileId);

  /**
   * Exclusively locks every guard a Profile link transition may write: home, active and pending
   * shares, plus the invitation's explicit reoffer Households.
   */
  void lockProfileTransitionAcrossHouseholds(UUID profileId, List<UUID> additionalHouseholdIds);

  /** Exclusively locks the Profile's home and every current or historical share Household. */
  void lockProfileDeletionAcrossHouseholds(UUID profileId);

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

  /** Moves the Profile only while it still belongs to the Household used for the decision. */
  boolean tryRehome(UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId);

  /**
   * Moves a Personal Profile with its Account. The Profile must still belong to {@code
   * expectedHouseholdId}, its Account's Household; anything else is a broken invariant and throws.
   */
  void rehomePersonalProfile(UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId);

  /** Locks and deletes the Profile only while no Account uses it as a Personal Profile. */
  boolean tryDeleteUnlinked(UUID profileId);

  /** Renames the Profile. The database checks name uniqueness at commit. */
  boolean tryRename(UUID profileId, String name);

  boolean trySetPicture(UUID profileId, String picture);

  /** Writes the PIN hash. Null removes it. The database rejects blank hashes. */
  boolean trySetPinHash(UUID profileId, String pinHash);

  /**
   * The row re-read from the database, not from Hibernate's first-level cache: a policy decision
   * inside this transaction already JPA-loaded the row, and after the jOOQ write the managed copy
   * is stale because jOOQ writes do not refresh managed entities.
   */
  Optional<Profile> findByIdAndReloadFromDatabase(UUID profileId);
}
