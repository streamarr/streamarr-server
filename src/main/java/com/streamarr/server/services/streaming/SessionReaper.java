package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionReaper {

  private final StreamingService streamingService;
  private final StreamingProperties properties;
  private final ProducerLifecycleService producerLifecycle;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = Instant.now();
    for (var session : streamingService.getAllSessions()) {
      try {
        processSession(session, now);
      } catch (RuntimeException e) {
        log.error("Failed to reap session {}", session.getSessionId(), e);
      }
    }
  }

  // Dead producers are not the reaper's concern: detection and recovery happen at request time in
  // SegmentDeliveryCoordinator, and a dead producer nobody watches is just an idle session.
  private void processSession(StreamSession session, Instant now) {
    if (isExpired(session, now)) {
      log.info("Reaping expired session {}", session.getSessionId());
      streamingService.destroySession(session.getSessionId());
      return;
    }

    if (isIdle(session, now) && session.hasActiveTranscodes()) {
      log.info("Suspending idle session {}", session.getSessionId());
      producerLifecycle.suspend(session);
    }
  }

  private boolean isExpired(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionRetention().toSeconds();
  }

  private boolean isIdle(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeout().toSeconds();
  }
}
