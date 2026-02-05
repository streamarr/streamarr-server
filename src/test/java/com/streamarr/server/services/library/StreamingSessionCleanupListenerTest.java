package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.streaming.StreamingService;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Session Cleanup Listener Tests")
public class StreamingSessionCleanupListenerTest {

  private final FakeStreamingService fakeStreamingService = new FakeStreamingService();
  private final StreamingSessionCleanupListener listener =
      new StreamingSessionCleanupListener(fakeStreamingService);

  @Test
  @DisplayName(
      "Should terminate streaming sessions matching media file IDs when LibraryRemovedEvent is received")
  void shouldTerminateMatchingSessionsWhenLibraryRemovedEventReceived() {
    var mediaFileId = UUID.randomUUID();
    var session = buildSessionForMediaFile(mediaFileId);
    fakeStreamingService.addSession(session);

    var event = new LibraryRemovedEvent("/library/path", Set.of(mediaFileId));
    listener.onLibraryRemoved(event);

    assertThat(fakeStreamingService.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should not terminate sessions for unrelated media files when LibraryRemovedEvent is received")
  void shouldNotTerminateUnrelatedSessionsWhenLibraryRemovedEventReceived() {
    var unrelatedMediaFileId = UUID.randomUUID();
    var unrelatedSession = buildSessionForMediaFile(unrelatedMediaFileId);
    fakeStreamingService.addSession(unrelatedSession);

    var removedMediaFileId = UUID.randomUUID();
    var event = new LibraryRemovedEvent("/library/path", Set.of(removedMediaFileId));
    listener.onLibraryRemoved(event);

    assertThat(fakeStreamingService.getAllSessions()).hasSize(1);
    assertThat(fakeStreamingService.getAllSessions().iterator().next().getMediaFileId())
        .isEqualTo(unrelatedMediaFileId);
  }

  @Test
  @DisplayName("Should handle empty media file IDs gracefully without terminating any sessions")
  void shouldHandleEmptyMediaFileIdsGracefully() {
    var session = buildSessionForMediaFile(UUID.randomUUID());
    fakeStreamingService.addSession(session);

    var event = new LibraryRemovedEvent("/library/path", Set.of());
    listener.onLibraryRemoved(event);

    assertThat(fakeStreamingService.getAllSessions()).hasSize(1);
  }

  private StreamSession buildSessionForMediaFile(UUID mediaFileId) {
    return StreamSession.builder().sessionId(UUID.randomUUID()).mediaFileId(mediaFileId).build();
  }

  private static class FakeStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return sessions.values();
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }
  }
}
