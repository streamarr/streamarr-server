package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.library.LibraryOperationMetrics.RequestOutcome;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts scans and refreshes as stored library operations (ADR 0027). One transaction decides on
 * the library's row, gives the library its busy status, stores the operation and schedules the
 * operation's one scheduled task. A busy or missing library refuses the request, and nothing is
 * queued. The status change is published after the transaction commits.
 */
@Service
@Builder
@RequiredArgsConstructor
public class LibraryOperationService {

  private final LibraryOperationRepository libraryOperations;
  private final LibraryOperationScheduling scheduling;
  private final MutationTransactions mutationTransactions;
  private final LibraryMutationTransaction libraryMutationTransaction;
  private final ApplicationEventPublisher eventPublisher;
  private final LibraryOperationMetrics metrics;

  /**
   * @return the library as the scan left it, already {@code SCANNING}, or why it refused the scan
   * @throws LibraryOperationNotStartedException when the transaction failed, so nothing started
   * @throws ResourceBusyException when the library's row stayed locked past the bounded wait
   */
  public Outcome<Library, LibraryOperationRejection> startLibraryScan(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID libraryId) {
    return start(
        NewLibraryOperation.scan(libraryId),
        newLibraryOperation ->
            libraryMutationTransaction.execute(
                identity,
                new Intent.ScanLibrary(libraryId),
                () -> storeAndSchedule(newLibraryOperation)));
  }

  /**
   * @return the library as the refresh left it, already {@code REFRESHING}, or why it refused the
   *     refresh
   * @throws LibraryOperationNotStartedException when the transaction failed, so nothing started
   * @throws ResourceBusyException when the library's row stayed locked past the bounded wait
   */
  public Outcome<Library, LibraryOperationRejection> startLibraryRefresh(
      @NonNull AuthenticatedIdentity identity,
      @NonNull UUID libraryId,
      @NonNull ImageRefreshMode imageRefreshMode) {
    return start(
        NewLibraryOperation.refresh(libraryId, imageRefreshMode),
        newLibraryOperation ->
            libraryMutationTransaction.execute(
                identity,
                new Intent.RefreshLibrary(libraryId),
                () -> storeAndSchedule(newLibraryOperation)));
  }

  /**
   * Starts a scan that no user requested, such as a scan for the facts of a new probe version. The
   * System actor requests it, so no authorization applies.
   */
  public Outcome<Library, LibraryOperationRejection> startLibraryScanAsSystemActor(
      @NonNull UUID libraryId) {
    var outcome = new AtomicReference<Outcome<Library, LibraryOperationRejection>>();
    SystemWork.runAsSystemActor(
        () -> outcome.set(start(NewLibraryOperation.scan(libraryId), this::storeAndSchedule)));
    return outcome.get();
  }

  /**
   * Stores the first scan of a library that the caller's transaction added, so the library and its
   * first scan commit or roll back together.
   *
   * @return the library, already {@code SCANNING}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Library storeFirstLibraryScan(@NonNull UUID libraryId) {
    return switch (libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId))) {
      case StoreOutcome.Stored(var libraryOperation, var library) ->
          scheduleAndPublish(libraryOperation, library);
      case StoreOutcome.Rejected(var rejection) ->
          throw new IllegalStateException(
              "A library added in this transaction refused its first scan: " + rejection);
    };
  }

  private Outcome<Library, LibraryOperationRejection> start(
      NewLibraryOperation newLibraryOperation, LibraryOperationWrite write) {
    var kind = newLibraryOperation.kind();
    Outcome<Library, LibraryOperationRejection> outcome;
    try {
      outcome =
          mutationTransactions.write(() -> write.write(newLibraryOperation), _ -> Optional.empty());
    } catch (DataAccessException | LibraryOperationNotScheduledException | TransactionException e) {
      metrics.countRequest(kind, RequestOutcome.FAILED);
      throw new LibraryOperationNotStartedException(e);
    } catch (ResourceBusyException e) {
      metrics.countRequest(kind, RequestOutcome.FAILED);
      throw e;
    }

    metrics.countRequest(kind, requestOutcome(outcome));
    return outcome;
  }

  @FunctionalInterface
  private interface LibraryOperationWrite {
    Library write(NewLibraryOperation newLibraryOperation);
  }

  private Library storeAndSchedule(NewLibraryOperation newLibraryOperation) {
    return switch (libraryOperations.storeLibraryOperation(newLibraryOperation)) {
      case StoreOutcome.Stored(var libraryOperation, var library) ->
          scheduleAndPublish(libraryOperation, library);
      case StoreOutcome.Rejected(var rejection) -> throw new MutationRejection(rejection);
    };
  }

  private Library scheduleAndPublish(LibraryOperation libraryOperation, Library library) {
    scheduling.scheduleExecution(libraryOperation.id());
    eventPublisher.publishEvent(new LibraryStatusChangedEvent(library.getId()));
    return library;
  }

  private static RequestOutcome requestOutcome(
      Outcome<Library, LibraryOperationRejection> outcome) {
    return outcome.fold(
        _ -> RequestOutcome.STORED,
        rejections ->
            switch (rejections.getFirst()) {
              case LibraryOperationRejection.LibraryNotFound _ -> RequestOutcome.NOT_FOUND;
              case LibraryOperationRejection.LibraryBusy _ -> RequestOutcome.BUSY;
            });
  }
}
