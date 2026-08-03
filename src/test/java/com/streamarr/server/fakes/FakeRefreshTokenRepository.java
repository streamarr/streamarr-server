package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeRefreshTokenRepository extends FakeJpaRepository<RefreshToken>
    implements RefreshTokenRepository {

  /** Mirrors uq_refresh_token_digest and uq_refresh_token_predecessor. */
  @Override
  public synchronized <S extends RefreshToken> S save(S token) {
    requireUnique(token, RefreshToken::getDigest, "uq_refresh_token_digest");
    requireUnique(token, RefreshToken::getPredecessorId, "uq_refresh_token_predecessor");
    return super.save(token);
  }

  private void requireUnique(
      RefreshToken token, Function<RefreshToken, Object> column, String constraint) {
    var value = column.apply(token);
    if (value == null) {
      return;
    }

    var conflicts =
        database.values().stream()
            .anyMatch(
                existing ->
                    !existing.getId().equals(token.getId())
                        && value.equals(column.apply(existing)));
    if (conflicts) {
      throw new DataIntegrityViolationException(constraint);
    }
  }

  @Override
  public Optional<UUID> findSessionIdByDigest(String digest) {
    return findByDigest(digest).map(RefreshToken::getSessionId);
  }

  @Override
  public Optional<String> findSuccessorDigest(UUID predecessorId) {
    return database.values().stream()
        .filter(token -> predecessorId.equals(token.getPredecessorId()))
        .map(RefreshToken::getDigest)
        .findFirst();
  }

  @Override
  public Optional<RefreshToken> findByDigest(String digest) {
    return database.values().stream().filter(token -> digest.equals(token.getDigest())).findFirst();
  }

  @Override
  public boolean isActiveToken(UUID sessionId, String digest, Instant now) {
    return database.values().stream()
        .anyMatch(
            token ->
                sessionId.equals(token.getSessionId())
                    && digest.equals(token.getDigest())
                    && token.getStatus() == RefreshTokenStatus.ACTIVE
                    && token.getExpiresAt().isAfter(now));
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
  public void revokeAllForSession(UUID sessionId, Instant now) {
    database.values().stream()
        .filter(token -> sessionId.equals(token.getSessionId()))
        .forEach(token -> token.setStatus(RefreshTokenStatus.REVOKED));
  }
}
