package com.streamarr.server.services.library;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.services.library.events.LibraryRemovedEvent;
import com.streamarr.server.services.streaming.StreamSessionLifecycleTransactions;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingSessionCleanupListener {

  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final Clock clock;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLibraryRemoved(LibraryRemovedEvent event) {
    if (event.mediaFileIds().isEmpty()) {
      return;
    }

    try {
      var termination =
          MediaStreamTermination.builder()
              .mediaFileIds(event.mediaFileIds())
              .reason(StreamSessionTerminalReason.SOURCE_DELETED)
              .terminalAt(clock.instant())
              .build();
      lifecycleTransactions.terminalizeByMediaFiles(termination);
    } catch (RuntimeException exception) {
      log.warn("Failed to terminate streams for removed library media", exception);
    }
  }
}
