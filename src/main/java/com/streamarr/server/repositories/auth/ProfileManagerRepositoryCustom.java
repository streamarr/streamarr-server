package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ProfileManagerRepositoryCustom {

  /**
   * Whether the Account directly manages the Profile, held with a shared row lock until the
   * surrounding transaction ends, so the grant cannot be revoked before the write it authorizes
   * commits.
   */
  boolean lockDirectManagement(UUID accountId, UUID profileId);

  /** Grants once: false when the relationship already exists (ON CONFLICT DO NOTHING). */
  boolean tryGrantDirectManagement(UUID accountId, UUID profileId);

  /**
   * Removes once: false when no grant remains. At commit, the database requires eligible home
   * supervision.
   */
  boolean tryRevokeDirectManagement(UUID accountId, UUID profileId);
}
