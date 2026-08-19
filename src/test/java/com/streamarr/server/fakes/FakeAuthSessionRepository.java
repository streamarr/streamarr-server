package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeAuthSessionRepository extends FakeJpaRepository<AuthSession>
    implements AuthSessionRepository {

  @Override
  public boolean isLive(UUID sessionId, UUID accountId) {
    return findById(sessionId)
        .filter(session -> session.getAccountId().equals(accountId))
        .filter(session -> session.getRevokedAt() == null)
        .isPresent();
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
              stored.setContextHouseholdId(session.getContextHouseholdId());
              stored.setSelectedProfileId(session.getSelectedProfileId());
              return true;
            })
        .orElse(false);
  }
}
