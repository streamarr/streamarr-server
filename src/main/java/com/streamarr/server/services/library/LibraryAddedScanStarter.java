package com.streamarr.server.services.library;

import com.streamarr.server.services.events.library.LibraryAddedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The first scan of a new library starts only once the library row is committed: a scan that began
 * for a rolled-back insert would process files for a library that does not exist.
 */
@Component
@RequiredArgsConstructor
public class LibraryAddedScanStarter {

  private final LibraryScanTrigger scanTrigger;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLibraryAdded(LibraryAddedEvent event) {
    scanTrigger.triggerAsyncScan(event.libraryId());
  }
}
