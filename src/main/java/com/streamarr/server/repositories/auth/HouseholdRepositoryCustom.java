package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.List;
import java.util.UUID;

public interface HouseholdRepositoryCustom {

  void refresh(Household household);

  List<Household> findAdministrationPage(MediaPaginationOptions options);

  /**
   * Locks the Household row for the caller's transaction, serializing destructive transitions.
   * False when the Household no longer exists after a competing transaction commits.
   */
  boolean lockById(UUID householdId);

  /**
   * @return true when the Household existed and was renamed
   */
  boolean tryRename(UUID householdId, String name);
}
