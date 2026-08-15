package com.streamarr.server.services.auth;

import java.util.UUID;

public interface ProfileSelectionCleaner {

  /**
 * Clears the selected profile for a household.
 *
 * @param profileId   the profile identifier to clear
 * @param householdId the household identifier associated with the profile
 * @return the cleanup result
 */
int clear(UUID profileId, UUID householdId);
}
