package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Builder
public class SessionReaper {

  private final StreamingService streamingService;
  private final StreamingProperties properties;
  private final ProducerLifecycleService producerLifecycle;
  private final PlaybackSessionService playbackSessionService;
  private final ServerSettingsService serverSettings;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = clock.instant();
    var retention = serverSettings.idleSegmentRetention();
    reapExpiredPlayback(now, retention);
    for (var session : streamingService.getAllSessions()) {
      try {
        processSession(session, now, retention);
      } catch (RuntimeException e) {
        log.error("Failed to reap session {}", session.getSessionId(), e);
      }
    }
  }

  // Dead producers are not the reaper's concern: detection and recovery happen at request time in
  // SegmentDeliveryCoordinator, and a dead producer nobody watches is just an idle session.
  private void processSession(StreamSession session, Instant now, Duration retention) {
    if (isExpired(session, now, retention)) {
      log.info("Reaping expired session {}", session.getSessionId());
      destroy(session.getSessionId());
      return;
    }

    if (isIdle(session, now) && session.hasActiveTranscodes()) {
      log.info("Suspending idle session {}", session.getSessionId());
      producerLifecycle.suspend(session.getSessionId());
    }
  }

  private void reapExpiredPlayback(Instant now, Duration retention) {
    try {
      playbackSessionService.reapExpired(now, retention);
    } catch (RuntimeException failure) {
      log.warn("Failed to reap expired logical playback sessions", failure);
    }
  }

  private void destroy(UUID streamSessionId) {
    closePlaybackSession(streamSessionId);
    streamingService.destroySession(streamSessionId);
  }

  private void closePlaybackSession(UUID streamSessionId) {
    try {
      playbackSessionService.closeOwningStream(streamSessionId);
    } catch (RuntimeException failure) {
      log.warn("Failed to close logical playback for expired stream {}", streamSessionId, failure);
    }
  }

  private boolean isExpired(StreamSession session, Instant now, Duration retention) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > retention.toSeconds();
  }

  private boolean isIdle(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeout().toSeconds();
  }
}
