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

  /**
   * Associates an account with a household.
   *
   * @param accountId   the account identifier
   * @param householdId the household identifier
   */
  public void registerAccountHome(UUID accountId, UUID householdId) {
    accountHomes.put(accountId, householdId);
  }

  /**
   * Reports whether the specified playback authority is currently valid.
   *
   * @param authority the playback authority to evaluate
   * @return never returns because playback authority is not configured
   * @throws UnsupportedOperationException always, because playback authority is not configured
   */
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

  /**
   * Updates the active profile selection for a live authentication session.
   *
   * @param session the session containing the updated profile selection
   * @param now the current time
   * @return {@code true} if the session exists and has not been revoked, {@code false} otherwise
   */
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

  /**
   * Clears a profile selection from live sessions associated with a household.
   *
   * @param profileId   the profile whose selection is cleared
   * @param householdId the household associated with the sessions
   * @return the number of sessions whose selection was cleared
   */
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

  /**
   * Clears active profile selections for all sessions belonging to an account.
   *
   * @param accountId the account whose active profile selections are cleared
   * @return the number of sessions whose active profile selection was cleared
   */
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
