package com.streamarr.server.services.library;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.streaming.StreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingSessionCleanupListener {

  private final StreamingService streamingService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLibraryRemoved(LibraryRemovedEvent event) {
    try {
      if (event.mediaFileIds().isEmpty()) {
        return;
      }
      streamingService.getAllSessions().stream()
          .filter(session -> event.mediaFileIds().contains(session.getMediaFileId()))
          .map(StreamSession::getSessionId)
          .forEach(streamingService::destroySession);
    } catch (Exception e) {
      log.warn("Failed to terminate streaming sessions: {}", e.getMessage());
    }
  }
}
