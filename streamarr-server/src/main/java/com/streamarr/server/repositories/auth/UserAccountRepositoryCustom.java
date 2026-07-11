package com.streamarr.server.repositories.auth;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepositoryCustom {

  Optional<AccountCredential> findCredentialById(UUID accountId);

  /**
   * Locks an enabled account only while its password hash still matches the caller's snapshot. This
   * scalar check avoids returning a stale managed entity from Hibernate's first-level cache.
   */
  boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash);
}
