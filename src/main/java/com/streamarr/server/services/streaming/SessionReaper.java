package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
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
  private final TranscodeExecutor transcodeExecutor;
  private final StreamingProperties properties;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = Instant.now();

    for (var session : streamingService.getAllSessions()) {
      var sessionId = session.getSessionId();
      var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();

      if (idleSeconds > properties.sessionTimeoutSeconds()
          && session.getActiveRequestCount().get() == 0) {
        log.info("Reaping idle session {} (idle {}s)", sessionId, idleSeconds);
        streamingService.destroySession(sessionId);
        continue;
      }

      var handle = session.getHandle();
      if (handle != null
          && handle.status() == TranscodeStatus.ACTIVE
          && !transcodeExecutor.isRunning(sessionId)) {
        log.warn("FFmpeg process died for session {}", sessionId);
        session.setHandle(new TranscodeHandle(handle.processId(), TranscodeStatus.FAILED));
      }
    }
  }
}
