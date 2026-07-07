package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.UUID;

public interface RefreshTokenRepositoryCustom {

  /**
   * Atomically consumes an ACTIVE, unexpired token: flips it to ROTATED and stamps rotated_at.
   * Exactly one concurrent caller sees 1 affected row.
   */
  int consumeActiveToken(String digest, Instant now);

  void revokeAllForSession(UUID sessionId);
}
