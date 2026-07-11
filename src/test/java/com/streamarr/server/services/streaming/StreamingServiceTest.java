package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Service Defaults Tests")
class StreamingServiceTest {

  @Test
  @DisplayName("Should destroy runtime state when default termination is requested")
  void shouldDestroyRuntimeStateWhenDefaultTerminationIsRequested() {
    var service = new DefaultStreamingService();
    var session = streamSession();
    service.addSession(session);

    var terminated = service.terminateRuntime(session.getSessionId());

    assertThat(terminated).isTrue();
    assertThat(service.accessSession(session.getSessionId())).isEmpty();
    assertThat(service.destroyedIds).containsExactly(session.getSessionId());
  }

  @Test
  @DisplayName("Should terminate every runtime session when default shutdown is requested")
  void shouldTerminateEveryRuntimeSessionWhenDefaultShutdownIsRequested() {
    var service = new DefaultStreamingService();
    var sessions = List.of(streamSession(), streamSession(), streamSession());
    sessions.forEach(service::addSession);

    service.shutdownRuntime();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.destroyedIds)
        .containsExactlyElementsOf(sessions.stream().map(StreamSession::getSessionId).toList());
  }

  private static StreamSession streamSession() {
    return StreamSession.builder().sessionId(UUID.randomUUID()).build();
  }

  private static final class DefaultStreamingService implements StreamingService {

    private final LinkedHashMap<UUID, StreamSession> sessions = new LinkedHashMap<>();
    private final List<UUID> destroyedIds = new ArrayList<>();

    private void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
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
