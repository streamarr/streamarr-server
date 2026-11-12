package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountShareFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Custom Account queries and transitions; {@code try*} methods report whether their update ran. */
public interface UserAccountRepositoryCustom {

  void refresh(UserAccount account);

  /**
   * The Account whose email equals {@code email} under PostgreSQL's {@code lower()}, the comparison
   * {@code uq_user_account_email} enforces. Not a derived query: Spring Data derives {@code
   * IgnoreCase} with {@code upper()}, which folds some letters, such as U+0130 and U+0131,
   * differently.
   */
  Optional<UserAccount> findByEmailIgnoreCase(String email);

  /**
   * The Accounts beyond each Household's seek, keyed by Household, ordered by lowercased display
   * name, then ID.
   */
  Map<UUID, List<Row<UserAccount>>> rowsBeyond(Map<UUID, Seek> seeksByHousehold);

  /**
   * The Account's current enabled state and ServerAdmin authority, read as scalars so the
   * authorization slice never sees a first-level-cache copy. Empty when the Account does not exist.
   */
  Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId);

  /**
   * Like {@link #findAuthorityFacts}, but held with a shared row lock until the surrounding
   * transaction ends, so the authority cannot be revoked before the write it authorizes commits.
   */
  Optional<AccountAuthorityFacts> lockAuthorityFacts(UUID accountId);

  /**
   * The Account relationships that can authorize a Share transition, held stable with a shared row
   * lock until the surrounding transaction ends.
   */
  Optional<AccountShareFacts> lockShareFacts(UUID accountId);

  /**
   * The ids of the Household's HouseholdAdmins, enabled or not, held with a shared row lock in id
   * order until the surrounding transaction ends, so none of them changes role or Household before
   * the write that decided from them commits.
   */
  Set<UUID> lockHouseholdAdminIds(UUID householdId);

  /**
   * The Account that manages the Profile itself because the Profile is its unrestricted Personal
   * Profile, with that Account's and the Profile's rows held by a shared lock until the surrounding
   * transaction ends. Empty when no Account manages the Profile itself.
   */
  Optional<UUID> lockSelfManagerId(UUID profileId);

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
   * Holds the Account row FOR UPDATE until the transaction ends, after any transaction that holds
   * it to act on the Account's authority has committed; does nothing when there is no such Account.
   */
  void lockById(UUID accountId);

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
   * stale because jOOQ writes do not refresh managed entities.
   */
  Optional<UserAccount> findByIdAndReloadFromDatabase(UUID accountId);

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
