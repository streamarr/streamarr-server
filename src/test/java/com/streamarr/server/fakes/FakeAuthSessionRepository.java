package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class FakeAuthSessionRepository extends FakeJpaRepository<AuthSession>
    implements AuthSessionRepository {

  private final Map<UUID, UUID> accountHomes = new HashMap<>();

  public void registerAccountHome(UUID accountId, UUID householdId) {
    accountHomes.put(accountId, householdId);
  }

  @Override
  public boolean hasLivePlaybackAuthority(PlaybackAuthority authority) {
    throw new UnsupportedOperationException("Live playback authority is not configured");
  }

  @Override
  public List<AuthSession> findByAccountId(UUID accountId) {
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
  public boolean hasRow(UUID sessionId) {
    return findById(sessionId).isPresent();
  }

  @Override
  public boolean updateSelectionIfLive(AuthSession session, Instant now) {
    return findById(session.getId())
        .filter(stored -> stored.getRevokedAt() == null)
        .map(
            stored -> {
              stored.setActiveProfileId(session.getActiveProfileId());
              return true;
            })
        .orElse(false);
  }

  @Override
  public int clearProfileSelection(UUID profileId, UUID householdId, Instant now) {
    var matches =
        database.values().stream()
            .filter(session -> profileId.equals(session.getActiveProfileId()))
            .filter(session -> session.getRevokedAt() == null)
            .filter(session -> householdId.equals(accountHomes.get(session.getAccountId())))
            .toList();
    matches.forEach(session -> session.setActiveProfileId(null));
    return matches.size();
  }

  @Override
  public int clearAccountSelections(UUID accountId, Instant now) {
    var matches =
        database.values().stream()
            .filter(session -> accountId.equals(session.getAccountId()))
            .filter(session -> session.getActiveProfileId() != null)
            .toList();
    matches.forEach(session -> session.setActiveProfileId(null));
    return matches.size();
  }
}
