package com.streamarr.server.services.streaming;

import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingShutdownHook {

  private final StreamingService streamingService;

  @PreDestroy
  public void onShutdown() {
    var sessions = streamingService.getAllSessions();
    if (sessions.isEmpty()) {
      return;
    }

    log.info("Shutting down {} active streaming session(s)", sessions.size());
    for (var session : List.copyOf(sessions)) {
      try {
        streamingService.destroySession(session.getSessionId());
      } catch (Exception ex) {
        log.warn("Failed to destroy session {} during shutdown", session.getSessionId(), ex);
      }
    }
  }
}
