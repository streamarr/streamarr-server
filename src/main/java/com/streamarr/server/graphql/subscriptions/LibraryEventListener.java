package com.streamarr.server.graphql.subscriptions;

import com.streamarr.server.domain.Library;
import com.streamarr.server.graphql.dto.RemovedLibrary;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns committed library changes into libraryStatusChanged, libraryAdded and libraryRemoved
 * payloads. Each event reads the library once, for every subscriber, in a transaction of its own,
 * so the payload carries the library's latest committed row. A write inside a transaction is
 * delivered only after commit; a write without one is delivered at once.
 *
 * <p>Publication never fails the scan, refresh or commit that triggered it. A failure is counted on
 * {@value #PUBLICATION_FAILURES_METRIC} instead, and subscribers recover current state through
 * their ordinary queries.
 */
@Slf4j
@Component
public class LibraryEventListener {

  static final String PUBLICATION_FAILURES_METRIC =
      "streamarr.graphql.subscriptions.publication_failures";

  private final LibraryRepository libraryRepository;
  private final LibraryBroadcasters broadcasters;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate committedReads;
  private final MutexFactory<UUID> statusPublicationLocks = new MutexFactory<>();

  public LibraryEventListener(
      LibraryRepository libraryRepository,
      LibraryBroadcasters broadcasters,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this.libraryRepository = libraryRepository;
    this.broadcasters = broadcasters;
    this.meterRegistry = meterRegistry;
    // An AFTER_COMMIT listener still sees the committed transaction's persistence context, whose
    // copy of the library can predate a status that jOOQ wrote. A new transaction reads the row.
    this.committedReads = new TransactionTemplate(transactionManager);
    this.committedReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.committedReads.setReadOnly(true);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryStatusChanged(LibraryStatusChangedEvent event) {
    publish(broadcasters.getStatusChanges(), () -> broadcastCurrentStatus(event.libraryId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryAdded(LibraryAddedEvent event) {
    var additions = broadcasters.getAdditions();
    publish(additions, () -> currentLibrary(event.libraryId()).ifPresent(additions::broadcast));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onLibraryRemoved(LibraryRemovedEvent event) {
    var removedLibraries = broadcasters.getRemovedLibraries();
    publish(
        removedLibraries, () -> removedLibraries.broadcast(new RemovedLibrary(event.libraryId())));
  }

  // Writers on different threads can save one library's status back to back. Reading and
  // broadcasting under the library's lock keeps broadcasts in read order, so the last broadcast
  // carries the latest saved status, because each read sees the latest committed row.
  private void broadcastCurrentStatus(UUID libraryId) {
    var lock = statusPublicationLocks.getMutex(libraryId);
    lock.lock();
    try {
      currentLibrary(libraryId).ifPresent(broadcasters.getStatusChanges()::broadcast);
    } finally {
      lock.unlock();
    }
  }

  // The read's own transaction closes its persistence context, so delivery threads resolve a
  // detached library. A builder copy would drop the audit columns, which entity builders discard.
  private Optional<Library> currentLibrary(UUID libraryId) {
    return committedReads.execute(_ -> libraryRepository.findById(libraryId));
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
