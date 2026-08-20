package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
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
