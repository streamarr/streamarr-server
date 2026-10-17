package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackRequest;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackFormatAttempt;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.util.ArrayDeque;
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
class StreamingSessionCleanupListenerTest {

  private final FakeStreamingService fakeStreamingService = new FakeStreamingService();
  private final StreamingSessionCleanupListener listener =
      new StreamingSessionCleanupListener(
          fakeStreamingService, playbackServiceBuilder(fakeStreamingService).build());

  @Test
  @DisplayName("Should finish media cleanup when closing logical playback throws")
  void shouldFinishMediaCleanupWhenClosingLogicalPlaybackThrows() {
    var streams = new FakeCreatingStreamingService();
    var playback = playbackServiceBuilder(streams).build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    streams.addSession(buildSessionForMediaFile(command.mediaFileId()));
    streams.failNextDestroy(initial.currentFormatAttempt().streamSessionId());

    new StreamingSessionCleanupListener(streams, playback)
        .onLibraryRemoved(
            new LibraryRemovedEvent(
                UUID.randomUUID(), "/library/path", Set.of(command.mediaFileId())));

    assertThat(playback.access(playbackRequest(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should close pending playback for removed media while preserving unrelated playback")
  void shouldClosePendingPlaybackForRemovedMediaWhilePreservingUnrelatedPlayback() {
    var streams = new FakeCreatingStreamingService();
    var work = new ArrayDeque<Runnable>();
    var playback = playbackServiceBuilder(streams).executor(work::add).build();
    var removed = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(removed));
    var unrelated = startPlaybackBuilder().build();
    var retained = acceptedPlayback(playback.start(unrelated));
    var oldChildId = initial.currentFormatAttempt().streamSessionId();
    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(oldChildId, UUID.randomUUID(), "default"));
    streams.destroySession(oldChildId);
    assertThat(
            playback.access(playbackRequest(initial, removed)).orElseThrow().currentFormatAttempt())
        .isInstanceOf(PlaybackFormatAttempt.Pending.class);

    new StreamingSessionCleanupListener(streams, playback)
        .onLibraryRemoved(
            new LibraryRemovedEvent(
                UUID.randomUUID(), "/library/path", Set.of(removed.mediaFileId())));
    work.forEach(Runnable::run);

    assertThat(playback.access(playbackRequest(initial, removed))).isEmpty();
    assertThat(playback.access(playbackRequest(retained, unrelated))).contains(retained);
    assertThat(streams.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(retained.currentFormatAttempt().streamSessionId());
  }

  @Test
  @DisplayName(
      "Should terminate streaming sessions matching media file IDs when LibraryRemovedEvent is received")
  void shouldTerminateMatchingSessionsWhenLibraryRemovedEventReceived() {
    var mediaFileId = UUID.randomUUID();
    var session = buildSessionForMediaFile(mediaFileId);
    fakeStreamingService.addSession(session);

    var event = new LibraryRemovedEvent(UUID.randomUUID(), "/library/path", Set.of(mediaFileId));
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
    var event =
        new LibraryRemovedEvent(UUID.randomUUID(), "/library/path", Set.of(removedMediaFileId));
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

    var event = new LibraryRemovedEvent(UUID.randomUUID(), "/library/path", Set.of());
    listener.onLibraryRemoved(event);

    assertThat(fakeStreamingService.getAllSessions()).hasSize(1);
  }

  private StreamSession buildSessionForMediaFile(UUID mediaFileId) {
    return StreamSession.builder()
        .sessionId(UUID.randomUUID())
        .mediaFileId(mediaFileId)
        .authority(StreamSessionFixture.playbackAuthorityFor(UUID.randomUUID()))
        .build();
  }

  private static class FakeStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
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
