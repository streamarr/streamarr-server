package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed library changes into library-administration subscription payloads. Each event
 * loads the current library once, for every subscriber. A write inside a transaction is delivered
 * only after commit; a write without one is delivered at once.
 *
 * <p>Publication never fails the scan, refresh or commit that triggered it. A failure is counted on
 * {@value #PUBLICATION_FAILURES_METRIC} instead, and subscribers recover current state through
 * their ordinary queries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryAdministrationEventListener {

  static final String PUBLICATION_FAILURES_METRIC =
      "streamarr.graphql.subscriptions.publication_failures";

  private final LibraryRepository libraryRepository;
  private final LibraryAdministrationBroadcasters broadcasters;
  private final MeterRegistry meterRegistry;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryStatusChanged(LibraryStatusChangedEvent event) {
    var statusChanges = broadcasters.getStatusChanges();
    publish(
        statusChanges, () -> currentLibrary(event.libraryId()).ifPresent(statusChanges::broadcast));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryAdded(LibraryAddedEvent event) {
    var additions = broadcasters.getAdditions();
    publish(additions, () -> currentLibrary(event.libraryId()).ifPresent(additions::broadcast));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryRemoved(LibraryRemovedEvent event) {
    var removals = broadcasters.getRemovals();
    publish(removals, () -> removals.broadcast(new LibraryRemoval(event.libraryId())));
  }

  // A detached copy: the payload is resolved on delivery threads, outside any persistence context.
  private Optional<Library> currentLibrary(UUID libraryId) {
    return libraryRepository.findById(libraryId).map(library -> library.toBuilder().build());
  }

  private void publish(SubscriptionBroadcaster<?> broadcaster, Runnable publication) {
    try {
      publication.run();
    } catch (RuntimeException e) {
      meterRegistry.counter(PUBLICATION_FAILURES_METRIC, "field", broadcaster.field()).increment();
      log.warn("Could not publish a {} subscription payload.", broadcaster.field(), e);
    }
  }
}
