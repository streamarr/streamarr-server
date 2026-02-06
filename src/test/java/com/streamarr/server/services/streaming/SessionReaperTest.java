package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
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
    var properties =
        StreamingProperties.builder()
            .segmentDurationSeconds(6)
            .sessionTimeoutSeconds(60)
            .sessionRetentionSeconds(86400)
            .build();
    reaper =
        new SessionReaper(
            streamingService, executor, properties, new FakeStreamSessionRepository());
  }

  @Test
  @DisplayName("Should mark handle as suspended when session is idle past timeout")
  void shouldMarkHandleAsSuspendedWhenIdlePastTimeout() {
    var session = buildSession(Instant.now().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should preserve session when suspending idle session")
  void shouldPreserveSessionWhenSuspendingIdleSession() {
    var session = buildSession(Instant.now().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should destroy session when idle past retention timeout")
  void shouldDestroySessionWhenIdlePastRetentionTimeout() {
    var session = buildSession(Instant.now().minusSeconds(90_000));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("Should not suspend session when already suspended")
  void shouldNotSuspendSessionWhenAlreadySuspended() {
    var session = buildSession(Instant.now().minusSeconds(120));
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.SUSPENDED);
    assertThat(streamingService.accessSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should not suspend session when handles are failed")
  void shouldNotSuspendSessionWhenHandlesAreFailed() {
    var session = buildSession(Instant.now().minusSeconds(120));
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.FAILED));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(streamingService.accessSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should stop executor when suspending idle session")
  void shouldStopExecutorWhenSuspendingIdleSession() {
    var session = buildSession(Instant.now().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(executor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should preserve session when recently accessed")
  void shouldPreserveSessionWhenRecentlyAccessed() {
    var session = buildSession(Instant.now().minusSeconds(10));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("Should update handle to failed when FFmpeg process dies")
  void shouldUpdateHandleToFailedWhenFfmpegProcessDies() {
    var session = buildSession(Instant.now().minusSeconds(10));
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
    var session = buildSession(Instant.now().minusSeconds(10));
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

  @Test
  @DisplayName("Should skip already failed handles when suspending idle session")
  void shouldSkipAlreadyFailedHandlesWhenSuspendingIdleSession() {
    var session = buildSession(Instant.now().minusSeconds(120));
    session.setVariantHandle("1080p", new TranscodeHandle(100L, TranscodeStatus.ACTIVE));
    session.setVariantHandle("720p", new TranscodeHandle(101L, TranscodeStatus.FAILED));

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

    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getVariantHandle("1080p").status()).isEqualTo(TranscodeStatus.SUSPENDED);
    assertThat(session.getVariantHandle("720p").status()).isEqualTo(TranscodeStatus.FAILED);
  }

  private StreamSession buildSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildMpegtsSession();
    session.setLastAccessedAt(lastAccessedAt);
    return session;
  }

  private StreamSession buildAbrSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildMpegtsSession();
    session.setLastAccessedAt(lastAccessedAt);

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
