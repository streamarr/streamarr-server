package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface HouseholdRepositoryCustom {

  /**
   * Renames only — a single-column update loses no concurrent change to other columns. True while
   * the Household exists.
   */
  boolean tryRename(UUID householdId, String name);
}
