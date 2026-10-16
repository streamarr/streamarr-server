package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recalculates a library's letter index after the file watcher processes an item, unless a scan or
 * refresh runs in the library. A scan or refresh recalculates the letter index in the transaction
 * that ends the operation, while that transaction holds the library's row. The listener shares the
 * row before it reads the operation, so the listener waits behind an end in progress and then
 * counts the item.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryMetadataListener {

  private final LetterIndexRecalculator letterIndexRecalculator;
  private final LibraryOperationRepository libraryOperations;
  private final TransactionTemplate transactionTemplate;

  @EventListener
  public void onItemProcessed(ItemProcessedEvent event) {
    try {
      transactionTemplate.executeWithoutResult(
          _ -> recalculateUnlessALibraryOperationRuns(event.libraryId()));
    } catch (RuntimeException e) {
      log.error("Letter index recalculation failed for library: {}", event.libraryId(), e);
    }
  }

  private void recalculateUnlessALibraryOperationRuns(UUID libraryId) {
    if (!libraryOperations.tryLockLibraryWithoutLibraryOperation(libraryId)) {
      return;
    }

    letterIndexRecalculator.recalculateLetterIndex(libraryId);
  }
}
