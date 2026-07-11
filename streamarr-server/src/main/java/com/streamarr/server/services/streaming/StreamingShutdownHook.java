package com.streamarr.server.services.streaming;

import jakarta.annotation.PreDestroy;
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
    try {
      streamingService.shutdownRuntime();
    } catch (RuntimeException exception) {
      log.warn("Failed to stop streaming runtime during shutdown", exception);
    }
  }
}
