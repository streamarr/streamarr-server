package com.streamarr.server.services.auth;

import java.util.UUID;

public interface ProfileSelectionCleaner {

  int clear(UUID profileId, UUID householdId);
}
