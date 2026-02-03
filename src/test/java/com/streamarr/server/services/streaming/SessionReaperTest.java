package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    var properties = new StreamingProperties(8, 6, 60);
    reaper = new SessionReaper(streamingService, executor, properties);
  }

  @Test
  @DisplayName("shouldReapIdleSessionWithNoActiveRequests")
  void shouldReapIdleSessionWithNoActiveRequests() {
    var session = buildSession(Instant.now().minusSeconds(120), 0);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("shouldPreserveIdleSessionWithActiveRequests")
  void shouldPreserveIdleSessionWithActiveRequests() {
    var session = buildSession(Instant.now().minusSeconds(120), 1);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("shouldPreserveRecentlyAccessedSession")
  void shouldPreserveRecentlyAccessedSession() {
    var session = buildSession(Instant.now().minusSeconds(10), 0);
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.getSession(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("shouldUpdateHandleToFailedWhenFfmpegProcessDies")
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
  @DisplayName("shouldNotChangeHandleWhenFfmpegProcessIsRunning")
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

  private StreamSession buildSession(Instant lastAccessedAt, int activeRequests) {
    var sessionId = UUID.randomUUID();
    var session =
        StreamSession.builder()
            .sessionId(sessionId)
            .mediaFileId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .mediaProbe(
                MediaProbe.builder()
                    .duration(Duration.ofMinutes(120))
                    .framerate(24.0)
                    .width(1920)
                    .height(1080)
                    .videoCodec("h264")
                    .audioCodec("aac")
                    .bitrate(5_000_000)
                    .build())
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.REMUX)
                    .videoCodecFamily("h264")
                    .audioCodec("aac")
                    .containerFormat(ContainerFormat.MPEGTS)
                    .needsKeyframeAlignment(true)
                    .build())
            .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
            .createdAt(Instant.now())
            .lastAccessedAt(lastAccessedAt)
            .activeRequestCount(new AtomicInteger(activeRequests))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
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
