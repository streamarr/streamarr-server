package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryMetadataListener {

  private final LetterIndexRecalculator letterIndexRecalculator;
  private final LibraryOperationRepository libraryOperations;

  @EventListener
  public void onScanCompleted(ScanCompletedEvent event) {
    recalculateLetterIndex(event.libraryId());
  }

  // A refresh skips the recalculation of every item processed while it runs, so its end
  // recalculates the letter index once, whatever its conclusion.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onRefreshEnded(RefreshEndedEvent event) {
    recalculateLetterIndex(event.libraryId());
  }

  // A running scan recalculates the letter index when it completes, and a refresh when it ends.
  @EventListener
  public void onItemProcessed(ItemProcessedEvent event) {
    if (libraryOperations.existsLibraryOperationForLibrary(event.libraryId())) {
      return;
    }

    recalculateLetterIndex(event.libraryId());
  }

  private void recalculateLetterIndex(UUID libraryId) {
    try {
      letterIndexRecalculator.recalculateLetterIndex(libraryId);
    } catch (Exception e) {
      log.error("Letter index recalculation failed for library: {}", libraryId, e);
    }
  }
}
