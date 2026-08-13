package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepositoryCustom {

  boolean hasLivePlaybackAuthority(PlaybackAuthority authority);

  /** Revokes a live session, returning false when it was missing or already revoked. */
  boolean revoke(UUID sessionId, SessionRevocationReason reason, Instant now);

  /**
   * Persists only the remembered household/profile selection when the session is still live.
   * Returns false when the session is missing or revoked; revocation fields are never written from
   * the supplied entity.
   */
  boolean updateSelectionIfLive(AuthSession session, Instant now);

  /** Clears remembered selections for a profile in one household after its active share ends. */
  int clearProfileSelection(UUID profileId, UUID householdId, Instant now);

  /** Clears every remembered profile selection owned by an account after a home transfer. */
  int clearAccountSelections(UUID accountId, Instant now);

  /**
   * Asks the database whether the row exists, so a conditional update that matched nothing can say
   * which of its two causes applied. Deliberately not JpaRepository.existsById: a JPA query
   * auto-flushes the pending insert first and would answer about the persistence context rather
   * than about what statements on this connection can already see.
   */
  boolean hasRow(UUID sessionId);

  /**
   * Reads the session under a row-level write lock (SELECT … FOR UPDATE). Refresh acquires it
   * before touching tokens, in the same order revoke() locks, so refresh and revocation serialize
   * on the session row — a successor can never be inserted onto a just-revoked session.
   */
  Optional<AuthSession> lockById(UUID sessionId);
}
