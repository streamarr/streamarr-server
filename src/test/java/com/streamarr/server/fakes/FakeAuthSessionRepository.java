package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class FakeAuthSessionRepository extends FakeJpaRepository<AuthSession>
    implements AuthSessionRepository {

  @Override
  public Optional<AuthSession> lockById(UUID sessionId) {
    // Single-JVM fake: the row lock is a no-op; the guard logic is what the unit tests exercise.
    return findById(sessionId);
  }

  @Override
  public Optional<Long> revoke(UUID sessionId, SessionRevocationReason reason, Instant now) {
    return findById(sessionId)
        .filter(session -> session.getRevokedAt() == null)
        .map(
            session -> {
              session.setRevokedAt(now);
              session.setRevokedReason(reason);
              session.setSessionVersion(session.getSessionVersion() + 1);
              return session.getSessionVersion();
            });
  }
}
