package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepositoryCustom {

  /**
   * The Account's current enabled state and ServerAdmin authority, read as scalars so the
   * authorization slice never sees a first-level-cache copy. Empty when the Account does not exist.
   */
  Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId);

  /**
   * Locks an enabled account only while its password hash still matches the caller's snapshot. This
   * scalar check avoids returning a stale managed entity from Hibernate's first-level cache.
   */
  boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash);
}
