package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class SessionReaperTest {

  private FakeTranscodeExecutor executor;
  private InMemoryStreamingService streamingService;
  private SessionReaper reaper;

  @BeforeEach
  void setUp() {
    executor = new FakeTranscodeExecutor();
    streamingService = new InMemoryStreamingService();
    var properties = new StreamingProperties(8, 6, 60, null);
    reaper = new SessionReaper(streamingService, executor, properties);
  }

  @Test
  @DisplayName("Should reap session when idle with no active requests")
  void shouldReapSessionWhenIdleWithNoActiveRequests() {
    var session = buildSession(Instant.now().minusSeconds(120), 0);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should preserve session when idle but has active requests")
  void shouldPreserveSessionWhenIdleButHasActiveRequests() {
    var session = buildSession(Instant.now().minusSeconds(120), 1);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should preserve session when recently accessed")
  void shouldPreserveSessionWhenRecentlyAccessed() {
    var session = buildSession(Instant.now().minusSeconds(10), 0);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should update handle to failed when FFmpeg process dies")
  void shouldUpdateHandleToFailedWhenFfmpegProcessDies() {
    var session = buildSession(Instant.now().minusSeconds(10), 0);
    session.setHandle(new TranscodeHandle(1234L, TranscodeStatus.ACTIVE));
    streamingService.addSession(session);
    executor.markDead(session.getSessionId());

    reaper.reapSessions();

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(session.getHandle().processId()).isEqualTo(1234L);
  }

  @Test
  @DisplayName("Should not change handle when FFmpeg process is running")
  void shouldNotChangeHandleWhenFfmpegProcessIsRunning() {
    var session = buildSession(Instant.now().minusSeconds(10), 0);
    session.setHandle(new TranscodeHandle(1234L, TranscodeStatus.ACTIVE));
    streamingService.addSession(session);
    executor.start(
        com.streamarr.server.domain.streaming.TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .segmentDuration(6)
            .framerate(24.0)
            .transcodeDecision(session.getTranscodeDecision())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000)
            .build());

    reaper.reapSessions();

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should mark specific variant as failed when only that process dies")
  void shouldMarkSpecificVariantAsFailedWhenOnlyThatProcessDies() {
    var session = buildAbrSession(Instant.now().minusSeconds(10));
    streamingService.addSession(session);

    executor.markDead(session.getSessionId(), "1080p");

    reaper.reapSessions();

    assertThat(session.getVariantHandle("1080p").status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(session.getVariantHandle("720p").status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should not change handles when all variants are running")
  void shouldNotChangeHandlesWhenAllVariantsRunning() {
    var session = buildAbrSession(Instant.now().minusSeconds(10));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getVariantHandle("1080p").status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getVariantHandle("720p").status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  private StreamSession buildSession(Instant lastAccessedAt, int activeRequests) {
    var session = StreamSessionFixture.buildMpegtsSession();
    session.setLastAccessedAt(lastAccessedAt);
    session.getActiveRequestCount().set(activeRequests);
    return session;
  }

  private StreamSession buildAbrSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildMpegtsSession();
    session.setLastAccessedAt(lastAccessedAt);
    session.getActiveRequestCount().set(0);

    session.setVariantHandle("1080p", new TranscodeHandle(100L, TranscodeStatus.ACTIVE));
    session.setVariantHandle("720p", new TranscodeHandle(101L, TranscodeStatus.ACTIVE));

    executor.start(
        com.streamarr.server.domain.streaming.TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .segmentDuration(6)
            .framerate(24.0)
            .transcodeDecision(session.getTranscodeDecision())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000)
            .variantLabel("1080p")
            .build());
    executor.start(
        com.streamarr.server.domain.streaming.TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .segmentDuration(6)
            .framerate(24.0)
            .transcodeDecision(session.getTranscodeDecision())
            .width(1280)
            .height(720)
            .bitrate(3_000_000)
            .variantLabel("720p")
            .build());

    return session;
  }

  private static class InMemoryStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> getSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }
  }
}
