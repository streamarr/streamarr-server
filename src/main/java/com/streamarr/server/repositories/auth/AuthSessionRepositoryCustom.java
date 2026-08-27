package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepositoryCustom {

  /** Whether the session's live Account, Household, and Profile match the playback authority. */
  boolean isLive(PlaybackAuthority authority);

  /** Revokes a live session, returning false when it was missing or already revoked. */
  boolean revoke(UUID sessionId, SessionRevocationReason reason, Instant now);

  /**
   * @return the number of live sessions revoked
   */
  int revokeAllForAccount(UUID accountId, SessionRevocationReason reason, Instant now);

  /**
   * Nulls selected_profile_id on every live session, whoever owns it, that has the Profile selected
   * under that context Household: the Profile stopped being available there (ADR 0024 §Profile
   * sharing).
   */
  int clearProfileSelectionFromLiveSessions(UUID profileId, UUID householdId, Instant now);

  /**
   * Nulls context_household_id and selected_profile_id on the Account's live sessions whose context
   * is that Household: the visitor's presence there ended. A NULL context resolves to the
   * membership Household at the next token issuance.
   */
  int clearHouseholdContextFromAccountSessions(UUID accountId, UUID householdId, Instant now);

  /**
   * Persists only the remembered context Household and selected Profile when the session is still
   * live. Returns false when the session is missing or revoked; revocation fields are never written
   * from the supplied entity.
   */
  boolean updateSelectionIfLive(AuthSession session, Instant now);

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
