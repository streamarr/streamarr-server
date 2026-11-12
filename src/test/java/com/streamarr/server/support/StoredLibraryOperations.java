package com.streamarr.server.support;

import com.streamarr.server.domain.LibraryOperationConclusion;
import com.streamarr.server.domain.LibraryOperationEnd;
import com.streamarr.server.domain.NewLibraryOperation;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.services.audit.SystemWork;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores and ends library operations through the repository, each in its own transaction, without
 * scheduling an execution.
 */
@RequiredArgsConstructor
public final class StoredLibraryOperations {

  private final LibraryOperationRepository libraryOperations;
  private final PlatformTransactionManager transactionManager;

  /** Stores a scan of the library and returns the scan's library operation id. */
  public UUID storeScan(UUID libraryId) {
    var outcome =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId)));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored.id();
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The scan was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  /** Ends the operation as finished, as the System actor, which deletes its row. */
  public Optional<LibraryOperationConclusion> endAsSystemActor(UUID libraryOperationId) {
    var end =
        LibraryOperationEnd.builder()
            .libraryOperationId(libraryOperationId)
            .conclusion(LibraryOperationConclusion.FINISHED)
            .endedAt(Instant.now())
            .build();
    var ended = new AtomicReference<Optional<LibraryOperationConclusion>>();
    SystemWork.runAsSystemActor(
        () ->
            ended.set(
                new TransactionTemplate(transactionManager)
                    .execute(_ -> libraryOperations.tryEndLibraryOperation(end))));
    return ended.get();
  }
}
