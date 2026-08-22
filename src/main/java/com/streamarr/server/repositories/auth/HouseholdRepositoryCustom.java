package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.List;
import java.util.UUID;

public interface HouseholdRepositoryCustom {

  void refresh(Household household);

  List<Household> findAdministrationPage(MediaPaginationOptions options);

  /**
   * Renames only — a single-column update loses no concurrent change to other columns. True while
   * the Household exists.
   */
  boolean tryRename(UUID householdId, String name);
}
