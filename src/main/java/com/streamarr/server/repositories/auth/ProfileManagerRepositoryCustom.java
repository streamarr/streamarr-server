package com.streamarr.server.repositories.auth;

import java.util.Set;
import java.util.UUID;

public interface ProfileManagerRepositoryCustom {

  /**
   * Whether the Account directly manages the Profile, held with a shared row lock until the
   * surrounding transaction ends, so the grant cannot be revoked before the write it authorizes
   * commits.
   */
  boolean lockDirectManagement(UUID accountId, UUID profileId);

  /**
   * The ids of the Accounts that directly manage the Profile, held with a shared row lock in id
   * order until the surrounding transaction ends, so none of them stops managing it before the
   * write that decided from them commits.
   */
  Set<UUID> lockManagerIds(UUID profileId);

  /** Grants once: false when the relationship already exists (ON CONFLICT DO NOTHING). */
  boolean tryGrantDirectManagement(UUID accountId, UUID profileId);

  /**
   * Removes once: false when no grant remains. At commit, the database requires eligible home
   * supervision.
   */
  boolean tryRevokeDirectManagement(UUID accountId, UUID profileId);
}
