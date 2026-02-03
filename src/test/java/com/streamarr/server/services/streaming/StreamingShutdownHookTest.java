package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeStatus;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class StreamingShutdownHookTest {

  @Test
  @DisplayName("Should destroy all active sessions when shutdown hook fires")
  void shouldDestroyAllActiveSessionsWhenShutdownHookFires() {
    var service = new TrackingStreamingService();
    var session1 = buildSession();
    var session2 = buildSession();
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

  private StreamSession buildSession() {
    var session =
        StreamSession.builder()
            .sessionId(UUID.randomUUID())
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
            .lastAccessedAt(Instant.now())
            .activeRequestCount(new AtomicInteger(0))
            .build();
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.ACTIVE));
    return session;
  }

  private static class TrackingStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
    private final List<UUID> destroyedIds = new java.util.ArrayList<>();

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
  }
}
