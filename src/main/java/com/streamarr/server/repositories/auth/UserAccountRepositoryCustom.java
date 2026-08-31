package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountShareFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Custom Account queries and transitions; {@code try*} methods report whether their update ran. */
public interface UserAccountRepositoryCustom {

  void refresh(UserAccount account);

  Map<UUID, List<UserAccount>> findAdministrationPages(
      Set<UUID> householdIds, MediaPaginationOptions options);

  /**
   * The Account's current enabled state and ServerAdmin authority, read as scalars so the
   * authorization slice never sees a first-level-cache copy. Empty when the Account does not exist.
   */
  Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId);

  /**
   * The Account relationships that can authorize a Share transition, held stable with a shared row
   * lock until the surrounding transaction ends.
   */
  Optional<AccountShareFacts> findShareFacts(UUID accountId);

  /**
   * ADR 0024 "may use a Household": the Account is a member, or its Personal Profile is actively
   * shared into the Household. Read live, as scalars.
   */
  boolean mayUseHousehold(UUID accountId, UUID householdId);

  /** Every Household the Account may use right now: its membership Household first, then visits. */
  List<UUID> findUsableHouseholdIds(UUID accountId);

  /**
   * Locks every requested Account so credential transitions follow Account-before-credential order.
   * Rows are taken in PostgreSQL {@code uuid} order (not Java {@code UUID.compareTo}), the wait is
   * bounded by a transaction-local {@code lock_timeout}, and an active transaction is required.
   * Returns the ids that exist and are now locked.
   */
  Set<UUID> lockByIds(Set<UUID> accountIds, Duration timeout);

  /**
   * Locks an enabled account only while its password hash still matches the caller's snapshot. This
   * scalar check avoids returning a stale managed entity from Hibernate's first-level cache.
   */
  boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash);

  /**
   * Locks the Account row only while it is still an enabled ServerAdmin — the live authority a
   * credential issuer must hold. False means no row was locked.
   */
  boolean tryLockEnabledServerAdmin(UUID accountId);

  /**
   * Locks the Household's {@code household_guard} row (one per Household, created by V056's
   * trigger) and chooses the role for a newly accepted Account under that lock. The first Account
   * becomes HouseholdAdmin; later Accounts keep the invitation's requested role. Empty means the
   * Household no longer exists.
   */
  Optional<HouseholdRole> roleForNewAccount(UUID householdId, HouseholdRole requestedRole);

  /**
   * Whether the Account may be named a direct ProfileManager in the Household: a member whose own
   * Personal Profile is unrestricted, holding the Household role the eligibility demands.
   */
  boolean isEligibleProfileManager(
      UUID accountId, UUID householdId, ProfileManagerEligibility eligibility);

  boolean tryGrantServerAdmin(UUID accountId);

  boolean tryRevokeServerAdmin(UUID accountId);

  boolean tryPromoteToHouseholdAdmin(UUID accountId);

  boolean tryDemoteToHouseholdMember(UUID accountId);

  boolean tryDisable(UUID accountId);

  /**
   * The row re-read from the database, not from Hibernate's first-level cache: the transfer
   * decision JPA-loaded the row in this transaction, and after the jOOQ write the managed copy is
   * stale (the hybrid footgun).
   */
  Optional<UserAccount> findByIdAndRefresh(UUID accountId);

  /**
   * The conditional transfer write: household and role move together, and only when the row is
   * still where the decision saw it — a partial update that can never carry a stale password hash
   * or clobber a concurrent rename.
   */
  boolean tryTransfer(
      UUID accountId, UUID expectedHouseholdId, UUID destinationHouseholdId, HouseholdRole role);

  /** Deletes only while the Account still belongs to the Household used for the decision. */
  boolean tryDelete(UUID accountId, UUID expectedHouseholdId);

  boolean tryEnable(UUID accountId);

  /**
   * @return true when the Account existed and was renamed
   */
  boolean tryRename(UUID accountId, String displayName);

  /**
   * Writes the password hash and the audit columns, nothing else; a reset must not overwrite
   * unrelated concurrent changes.
   */
  boolean trySetPasswordHash(UUID accountId, String passwordHash);
}
