package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.domain.streaming.StreamSession;
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
  @DisplayName("Should stop runtime without durably destroying sessions when shutdown hook fires")
  void shouldStopRuntimeWithoutDurablyDestroyingSessionsWhenShutdownHookFires() {
    var service = new TrackingStreamingService();
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    service.addSession(session1);
    service.addSession(session2);

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getShutdownIds())
        .containsExactlyInAnyOrder(session1.getSessionId(), session2.getSessionId());
    assertThat(service.getDestroyedIds()).isEmpty();
  }

  @Test
  @DisplayName("Should not throw when no active sessions exist during shutdown")
  void shouldNotThrowWhenNoActiveSessionsExistDuringShutdown() {
    var service = new TrackingStreamingService();

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getShutdownIds()).isEmpty();
    assertThat(service.getDestroyedIds()).isEmpty();
  }

  @Test
  @DisplayName("Should isolate a runtime shutdown failure")
  void shouldIsolateRuntimeShutdownFailure() {
    var service = new TrackingStreamingService();
    service.failShutdown();
    var hook = new StreamingShutdownHook(service);

    assertThatNoException().isThrownBy(hook::onShutdown);

    assertThat(service.getShutdownAttempts()).isEqualTo(1);
  }

  private static class TrackingStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
    private final List<UUID> destroyedIds = new ArrayList<>();
    private final List<UUID> shutdownIds = new ArrayList<>();
    private boolean shutdownFails;
    private int shutdownAttempts;

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    List<UUID> getDestroyedIds() {
      return List.copyOf(destroyedIds);
    }

    List<UUID> getShutdownIds() {
      return List.copyOf(shutdownIds);
    }

    int getShutdownAttempts() {
      return shutdownAttempts;
    }

    void failShutdown() {
      shutdownFails = true;
    }

    @Override
    public void shutdownRuntime() {
      shutdownAttempts++;
      if (shutdownFails) {
        throw new IllegalStateException("simulated runtime shutdown failure");
      }
      shutdownIds.addAll(sessions.keySet());
      sessions.clear();
    }

    @Override
    public StreamSession createSession(CreateRuntimeStreamSessionCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
      destroyedIds.add(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      throw new UnsupportedOperationException();
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
