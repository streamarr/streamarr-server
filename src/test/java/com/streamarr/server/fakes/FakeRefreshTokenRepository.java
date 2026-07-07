package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class FakeRefreshTokenRepository extends FakeJpaRepository<RefreshToken>
    implements RefreshTokenRepository {

  @Override
  public Optional<RefreshToken> findByDigest(String digest) {
    return database.values().stream().filter(token -> digest.equals(token.getDigest())).findFirst();
  }

  /** Mirrors the conditional single-statement consume contract of the jOOQ implementation. */
  @Override
  public synchronized int consumeActiveToken(String digest, Instant now) {
    var match =
        database.values().stream()
            .filter(
                token ->
                    digest.equals(token.getDigest())
                        && token.getStatus() == RefreshTokenStatus.ACTIVE
                        && token.getExpiresAt().isAfter(now))
            .findFirst();

    if (match.isEmpty()) {
      return 0;
    }

    var token = match.get();
    token.setStatus(RefreshTokenStatus.ROTATED);
    token.setRotatedAt(now);
    return 1;
  }

  @Override
  public void revokeAllForSession(UUID sessionId) {
    database.values().stream()
        .filter(token -> sessionId.equals(token.getSessionId()))
        .forEach(token -> token.setStatus(RefreshTokenStatus.REVOKED));
  }
}
