package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Playback Session Access Service Tests")
class PlaybackSessionAccessServiceTest {

  @Test
  @DisplayName("Should return missing without database access when runtime session is absent")
  void shouldReturnMissingWithoutDatabaseAccessWhenRuntimeSessionAbsent() {
    PlaybackSessionAccessService service =
        new DefaultPlaybackSessionAccessService(
            new InMemoryStreamSessionRepository(),
            new ExplodingLifecycleTransactions(),
            new StreamSessionTransactionRetry(_ -> {}));

    var result = service.access(UUID.randomUUID(), playbackIdentity());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should reject non-playback scope without database access")
  void shouldRejectNonPlaybackScopeWithoutDatabaseAccess() {
    var sessionId = UUID.randomUUID();
    var registry = registryWithSession(sessionId);
    var service = accessService(registry);

    var result = service.access(sessionId, playbackIdentity(sessionId, TokenScope.PROFILE));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should reject mismatched token session id without database access")
  void shouldRejectMismatchedTokenSessionIdWithoutDatabaseAccess() {
    var sessionId = UUID.randomUUID();
    var registry = registryWithSession(sessionId);
    var service = accessService(registry);

    var result =
        service.access(sessionId, playbackIdentity(UUID.randomUUID(), TokenScope.PLAYBACK));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should reject missing playback profile without database access")
  void shouldRejectMissingPlaybackProfileWithoutDatabaseAccess() {
    var sessionId = UUID.randomUUID();
    var registry = registryWithSession(sessionId);
    var service = accessService(registry);
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.PLAYBACK)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .streamSessionId(sessionId)
            .build();

    var result = service.access(sessionId, identity);

    assertThat(result).isEmpty();
  }

  private PlaybackSessionAccessService accessService(RuntimeStreamSessionRegistry registry) {
    return new DefaultPlaybackSessionAccessService(
        registry, new ExplodingLifecycleTransactions(), new StreamSessionTransactionRetry(_ -> {}));
  }

  private RuntimeStreamSessionRegistry registryWithSession(UUID sessionId) {
    RuntimeStreamSessionRegistry registry = new InMemoryStreamSessionRepository();
    registry.save(StreamSession.builder().sessionId(sessionId).build());
    return registry;
  }

  private AuthenticatedIdentity playbackIdentity() {
    return playbackIdentity(UUID.randomUUID(), TokenScope.PLAYBACK);
  }

  private AuthenticatedIdentity playbackIdentity(UUID streamSessionId, TokenScope scope) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .sessionId(UUID.randomUUID())
        .scope(scope)
        .householdId(UUID.randomUUID())
        .householdRole(HouseholdRole.MEMBER)
        .profileId(UUID.randomUUID())
        .streamSessionId(streamSessionId)
        .build();
  }

  private static final class ExplodingLifecycleTransactions
      implements StreamSessionLifecycleTransactions {

    @Override
    public Optional<Instant> admit(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean activate(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public Optional<Instant> touchIfActiveAndOwnedBy(UUID streamSessionId, UUID profileId) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> terminalizeExpiredActiveSessions(java.time.Duration retention, int limit) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public java.util.Set<UUID> findAllSessionIds() {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> findTerminatingIds(int limit) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<UUID> terminalizeRevokedAuthSessions(int limit) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean terminalize(StreamSessionTermination termination) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean recordTerminationIntent(StreamSessionTermination termination) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public List<StreamSessionTermination> findTerminationIntents() {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean completeCreation(UUID streamSessionId) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean replayTerminationIntent(UUID streamSessionId) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean deleteTerminationIntent(UUID streamSessionId) {
      throw unexpectedDatabaseAccess();
    }

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      throw unexpectedDatabaseAccess();
    }

    private static AssertionError unexpectedDatabaseAccess() {
      return new AssertionError("local rejection must not touch durable authority");
    }
  }
}
