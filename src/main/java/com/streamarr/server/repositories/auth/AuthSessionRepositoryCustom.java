package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepositoryCustom {

  /**
   * Revokes an unrevoked session and bumps its version counter in one statement. Returns the new
   * session version, or empty when the session was already revoked (no double bump).
   */
  Optional<Long> revoke(UUID sessionId, SessionRevocationReason reason, Instant now);

  /**
   * Bumps an unrevoked session's version counter without revoking it, invalidating outstanding
   * access tokens while the session and refresh-token family remain live.
   */
  Optional<Long> bumpVersion(UUID sessionId, Instant now);

  /**
   * Persists only the remembered household/profile selection when the session is still live.
   * Returns false when the session is missing or revoked; revocation and version fields are never
   * written from the supplied entity.
   */
  boolean updateSelectionIfLive(AuthSession session, Instant now);

  /**
   * Reads the session under a row-level write lock (SELECT … FOR UPDATE). Refresh acquires it
   * before touching tokens, in the same order revoke() locks, so refresh and revocation serialize
   * on the session row — a successor can never be inserted onto a just-revoked session.
   */
  Optional<AuthSession> lockById(UUID sessionId);

  List<UUID> lockIdsByAccountIdOrderById(UUID accountId);
}
