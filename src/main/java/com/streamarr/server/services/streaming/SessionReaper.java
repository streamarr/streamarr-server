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
  private final StreamSessionRepository sessionRepository;

  @Scheduled(fixedDelayString = "${streaming.reaper-interval-ms:15000}")
  public void reapSessions() {
    var now = Instant.now();

    for (var session : streamingService.getAllSessions()) {
      if (isIdle(session, now)) {
        log.info("Reaping idle session {} (idle)", session.getSessionId());
        streamingService.destroySession(session.getSessionId());
        continue;
      }

      handleDeadProcesses(session);
    }
  }

  private boolean isIdle(StreamSession session, Instant now) {
    var idleSeconds = now.getEpochSecond() - session.getLastAccessedAt().getEpochSecond();
    return idleSeconds > properties.sessionTimeoutSeconds();
  }

  private void handleDeadProcesses(StreamSession session) {
    for (var entry : session.getVariantHandles().entrySet()) {
      var label = entry.getKey();
      var handle = entry.getValue();

      if (handle.status() != TranscodeStatus.ACTIVE) {
        continue;
      }
      if (transcodeExecutor.isRunning(session.getSessionId(), label)) {
        continue;
      }

      log.warn("FFmpeg process died for session {} variant {}", session.getSessionId(), label);
      session.setVariantHandle(
          label, new TranscodeHandle(handle.processId(), TranscodeStatus.FAILED));
      sessionRepository.save(session);
    }
  }
}
