package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import com.streamarr.server.services.events.library.LibraryItemAddedEvent;
import com.streamarr.server.services.events.library.LibraryItemRemovedEvent;
import com.streamarr.server.services.events.library.LibraryItemUpdatedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LibraryContentsEventListener {

  private final LibraryContentsBroadcasters broadcasters;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onItemAdded(LibraryItemAddedEvent event) {
    broadcasters.getAdditions().broadcast(event.libraryId(), event);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onItemUpdated(LibraryItemUpdatedEvent event) {
    broadcasters.getUpdates().broadcast(event.libraryId(), event);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onItemRemoved(LibraryItemRemovedEvent event) {
    broadcasters.getRemovals().broadcast(event.libraryId(), event);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onCountsChanged(LibraryCountsChangedEvent event) {
    broadcasters.getCounts().broadcast(event.libraryId(), event.libraryId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onLibraryRemoved(LibraryRemovedEvent event) {
    broadcasters.getAdditions().complete(event.libraryId());
    broadcasters.getUpdates().complete(event.libraryId());
    broadcasters.getRemovals().complete(event.libraryId());
    broadcasters.getCounts().complete(event.libraryId());
  }
}
