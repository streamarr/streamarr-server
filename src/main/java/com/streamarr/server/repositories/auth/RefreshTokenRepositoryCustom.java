package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepositoryCustom {

  /**
   * Atomically consumes an ACTIVE, unexpired token: flips it to ROTATED and stamps rotated_at.
   * Exactly one concurrent caller sees 1 affected row.
   */
  int consumeActiveToken(String digest, Instant now);

  void revokeAllForSession(UUID sessionId);

  /**
   * Reads the owning session id for a digest without loading the token as a managed entity — so a
   * subsequent JPA read of the same row returns fresh state instead of a stale first-level-cache
   * copy.
   */
  Optional<UUID> findSessionIdByDigest(String digest);

  /** Returns whether the digest names an ACTIVE token that expires strictly after {@code now}. */
  boolean isActiveToken(UUID sessionId, String digest, Instant now);
}
