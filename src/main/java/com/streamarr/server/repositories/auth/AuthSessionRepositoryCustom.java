package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
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

  /**
   * Bumps an unrevoked session's version counter without revoking it — every outstanding token dies
   * while the session itself stays alive for fresh issuance (password change keeps the caller
   * logged in).
   */
  Optional<Long> bumpVersion(UUID sessionId, Instant now);

  /**
   * Reads the session under a row-level write lock (SELECT … FOR UPDATE). Refresh acquires it
   * before touching tokens, in the same order revoke() locks, so refresh and revocation serialize
   * on the session row — a successor can never be inserted onto a just-revoked session.
   */
  Optional<AuthSession> lockById(UUID sessionId);
}
