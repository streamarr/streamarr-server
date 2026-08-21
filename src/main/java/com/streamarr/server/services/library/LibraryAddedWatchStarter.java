package com.streamarr.server.services.library;

import com.streamarr.server.services.events.library.LibraryAddedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LibraryAddedWatchStarter {

  private final LibraryWatchTrigger watchTrigger;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLibraryAdded(LibraryAddedEvent event) {
    watchTrigger.triggerAsyncWatch(event.filepathUri());
  }
}
