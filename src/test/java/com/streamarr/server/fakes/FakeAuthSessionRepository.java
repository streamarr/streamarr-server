package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeAuthSessionRepository extends FakeJpaRepository<AuthSession>
    implements AuthSessionRepository {

  @Override
  public boolean isLive(PlaybackAuthority authority) {
    return findById(authority.authSessionId())
        .filter(session -> session.getAccountId().equals(authority.accountId()))
        .filter(session -> authority.householdId().equals(session.getContextHouseholdId()))
        .filter(session -> authority.profileId().equals(session.getSelectedProfileId()))
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
  public int revokeAllForAccount(UUID accountId, SessionRevocationReason reason, Instant now) {
    var live =
        database.values().stream()
            .filter(session -> accountId.equals(session.getAccountId()))
            .filter(session -> session.getRevokedAt() == null)
            .toList();
    live.forEach(
        session -> {
          session.setRevokedAt(now);
          session.setRevokedReason(reason);
        });
    return live.size();
  }

  @Override
  public int clearSelections(UUID profileId, UUID householdId, Instant now) {
    var affected =
        database.values().stream()
            .filter(session -> profileId.equals(session.getSelectedProfileId()))
            .filter(session -> householdId.equals(session.getContextHouseholdId()))
            .filter(session -> session.getRevokedAt() == null)
            .toList();
    affected.forEach(session -> session.setSelectedProfileId(null));
    return affected.size();
  }

  @Override
  public int resetContextForAccount(UUID accountId, UUID householdId, Instant now) {
    var affected =
        database.values().stream()
            .filter(session -> accountId.equals(session.getAccountId()))
            .filter(session -> householdId.equals(session.getContextHouseholdId()))
            .filter(session -> session.getRevokedAt() == null)
            .toList();
    affected.forEach(
        session -> {
          session.setContextHouseholdId(null);
          session.setSelectedProfileId(null);
        });
    return affected.size();
  }

  @Override
  public boolean hasRow(UUID sessionId) {
    return findById(sessionId).isPresent();
  }

  @Override
  public int revokeAllForRegistrations(
      List<UUID> registrationIds, SessionRevocationReason reason, Instant now) {
    var matching =
        database.values().stream()
            .filter(session -> session.getRegistrationId() != null)
            .filter(session -> registrationIds.contains(session.getRegistrationId()))
            .filter(session -> session.getRevokedAt() == null)
            .toList();
    matching.forEach(
        session -> {
          session.setRevokedAt(now);
          session.setRevokedReason(reason);
        });
    return matching.size();
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
