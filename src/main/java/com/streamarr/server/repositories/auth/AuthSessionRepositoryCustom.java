package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepositoryCustom {

  /**
   * Revokes an unrevoked session and bumps its version counter in one statement. Returns the new
   * session version, or empty when the session was already revoked (no double bump).
   */
  Optional<Long> revoke(UUID sessionId, SessionRevocationReason reason, Instant now);
}
