package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
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
      if (isIdleAndUnused(session, now)) {
        log.info("Reaping idle session {} (idle)", session.getSessionId());
        streamingService.destroySession(session.getSessionId());
        continue;
      }

      if (hasDeadProcess(session)) {
        log.warn("FFmpeg process died for session {}", session.getSessionId());
        var handle = session.getHandle();
        session.setHandle(new TranscodeHandle(handle.processId(), TranscodeStatus.FAILED));
      }
    }
  }

  private boolean isIdleAndUnused(
      StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeoutSeconds()
        && session.getActiveRequestCount().get() == 0;
  }

  private boolean hasDeadProcess(
      StreamSession session) {
    var handle = session.getHandle();
    return handle != null
        && handle.status() == TranscodeStatus.ACTIVE
        && !transcodeExecutor.isRunning(session.getSessionId());
  }
}
