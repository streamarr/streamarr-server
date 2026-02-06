package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class StreamingShutdownHookTest {

  @Test
  @DisplayName("Should destroy all active sessions when shutdown hook fires")
  void shouldDestroyAllActiveSessionsWhenShutdownHookFires() {
    var service = new TrackingStreamingService();
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    service.addSession(session1);
    service.addSession(session2);

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds())
        .containsExactlyInAnyOrder(session1.getSessionId(), session2.getSessionId());
  }

  @Test
  @DisplayName("Should not throw when no active sessions exist during shutdown")
  void shouldNotThrowWhenNoActiveSessionsExistDuringShutdown() {
    var service = new TrackingStreamingService();

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds()).isEmpty();
  }

  private static class TrackingStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
    private final List<UUID> destroyedIds = new ArrayList<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    List<UUID> getDestroyedIds() {
      return List.copyOf(destroyedIds);
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
      destroyedIds.add(sessionId);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      throw new UnsupportedOperationException();
    }
  }
}
