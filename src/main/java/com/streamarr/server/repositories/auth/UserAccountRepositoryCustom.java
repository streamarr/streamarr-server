package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
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
   * ADR 0024 "may use a Household": the Account is a member, or its Personal Profile is actively
   * shared into the Household. Read live, as scalars.
   */
  boolean mayUseHousehold(UUID accountId, UUID householdId);

  /** Every Household the Account may use right now: its membership Household first, then visits. */
  List<UUID> findUsableHouseholdIds(UUID accountId);

  /**
   * Locks an enabled account only while its password hash still matches the caller's snapshot. This
   * scalar check avoids returning a stale managed entity from Hibernate's first-level cache.
   */
  boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash);

  /** Locks and revalidates the live authority required to issue one-time credentials. */
  boolean lockIfEnabledServerAdmin(UUID accountId);

  /**
   * Locks the Household's coordination row and chooses the role for a newly accepted Account. The
   * first Account becomes HouseholdAdmin; later Accounts keep the invitation's requested role.
   * Empty means the Household no longer exists.
   */
  Optional<HouseholdRole> roleForNewAccount(UUID householdId, HouseholdRole requestedRole);

  /**
   * Whether the Account is an eligible direct ProfileManager in the Profile's home Household.
   * Restricted Profiles additionally require a HouseholdAdmin manager.
   */
  boolean isEligibleProfileManager(
      UUID accountId, UUID householdId, boolean householdAdminRequired);

  boolean tryGrantServerAdmin(UUID accountId);

  boolean tryRevokeServerAdmin(UUID accountId);

  boolean tryPromoteToHouseholdAdmin(UUID accountId);

  boolean tryDemoteToHouseholdMember(UUID accountId);

  boolean tryDisable(UUID accountId);

  boolean tryEnable(UUID accountId);

  /**
   * @return true when the Account existed and was renamed
   */
  boolean tryRename(UUID accountId, String displayName);

  /** Writes only the password hash; a reset must not overwrite unrelated concurrent changes. */
  boolean trySetPasswordHash(UUID accountId, String passwordHash);
}
