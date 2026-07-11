package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepositoryCustom {

  /** Revokes an unrevoked session. Returns false when it was missing or already revoked. */
  boolean revoke(UUID sessionId, SessionRevocationReason reason, Instant now);

  /**
   * Persists only the remembered household/profile selection when the session is still live.
   * Returns false when the session is missing or revoked; revocation fields are never written from
   * the supplied entity.
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
