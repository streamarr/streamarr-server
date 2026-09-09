package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerRepositoryCustom {

  /** Grants once: false when the relationship already exists (ON CONFLICT DO NOTHING). */
  boolean tryGrantDirectManagement(UUID accountId, UUID profileId);

  /**
   * Removes once: false when no grant remains. At commit, the database requires eligible home
   * supervision.
   */
  boolean tryRevokeDirectManagement(UUID accountId, UUID profileId);
}
