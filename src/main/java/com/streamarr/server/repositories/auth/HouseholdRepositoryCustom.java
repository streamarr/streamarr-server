package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface HouseholdRepositoryCustom {

  void refresh(Household household);

  List<Household> findAdministrationPage(MediaPaginationOptions options);

  /**
   * Locks the Household row for the caller's transaction, serializing destructive transitions.
   * False when the Household no longer exists after a competing transaction commits.
   */
  boolean lockById(UUID householdId);

  /** Locks existing Households in database ID order until the caller's transaction ends. */
  Set<UUID> lockByIds(Set<UUID> householdIds);

  /**
   * @return true when the Household existed and was renamed
   */
  boolean tryRename(UUID householdId, String name);
}
