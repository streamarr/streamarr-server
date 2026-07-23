package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class FakeAuthSessionRepository extends FakeJpaRepository<AuthSession>
    implements AuthSessionRepository {

  @Override
  public boolean hasLivePlaybackAuthority(PlaybackAuthority authority) {
    throw new UnsupportedOperationException("Live playback authority is not configured");
  }

  @Override
  public java.util.List<AuthSession> findByAccountId(UUID accountId) {
    return database.values().stream()
        .filter(session -> accountId.equals(session.getAccountId()))
        .toList();
  }

  @Override
  public Optional<AuthSession> lockById(UUID sessionId) {
    // Single-JVM fake: the row lock is a no-op; the guard logic is what the unit tests exercise.
    return findById(sessionId);
  }

  @Override
  public boolean revoke(UUID sessionId, SessionRevocationReason reason, Instant now) {
    return findById(sessionId)
        .filter(session -> session.getRevokedAt() == null)
        .map(
            session -> {
              session.setRevokedAt(now);
              session.setRevokedReason(reason);
              return true;
            })
        .orElse(false);
  }

  @Override
  public boolean updateSelectionIfLive(AuthSession session, Instant now) {
    return findById(session.getId())
        .filter(stored -> stored.getRevokedAt() == null)
        .map(
            stored -> {
              stored.setActiveHouseholdId(session.getActiveHouseholdId());
              stored.setActiveProfileId(session.getActiveProfileId());
              return true;
            })
        .orElse(false);
  }
}
