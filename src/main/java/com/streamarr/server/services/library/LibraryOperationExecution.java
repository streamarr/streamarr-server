package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.exceptions.LibraryScanFailedException;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.library.LibraryOperationMetrics.ExecutionResult;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One execution of a stored library operation, independent of the scheduler that runs it. Every
 * execution walks the library from the start. A shutdown that interrupts the execution leaves the
 * operation stored, so the operation runs again when a scheduler starts. Otherwise one transaction
 * ends the operation: it gives the library its final status, deletes the operation, and publishes
 * the status change and the end of the scan or refresh for listeners that run after the commit. An
 * end that cannot be saved escapes, so the scheduler runs the operation again with backoff.
 */
@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class LibraryOperationExecution {

  private final LibraryOperationRepository libraryOperations;
  private final LibraryRepository libraryRepository;
  private final LibraryFileWalk libraryFileWalk;
  private final FileDiscoveryRuns fileDiscoveryRuns;
  private final LibraryFolderCheck libraryFolderCheck;
  private final LibraryRefreshService libraryRefreshService;
  private final ApplicationEventPublisher eventPublisher;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final LibraryOperationMetrics metrics;

  /**
   * Runs the stored operation once.
   *
   * @return {@link LibraryOperationExecutionResult.Ended} when the operation ended or no longer
   *     exists, or {@link LibraryOperationExecutionResult.Interrupted} when a shutdown interrupted
   *     it; the interrupt flag is then set
   */
  public LibraryOperationExecutionResult execute(UUID libraryOperationId) {
    return libraryOperations
        .findLibraryOperation(libraryOperationId)
        .map(this::execute)
        .orElseGet(LibraryOperationExecutionResult.Ended::new);
  }

  private LibraryOperationExecutionResult execute(LibraryOperation operation) {
    var library =
        libraryRepository
            .findById(operation.libraryId())
            .orElseThrow(() -> new LibraryNotFoundException(operation.libraryId()));
    LibraryOperationConclusion conclusion;
    try {
      conclusion =
          switch (operation.kind()) {
            case SCAN -> scan(library);
            case REFRESH -> refresh(library, operation.imageRefreshMode().orElseThrow());
          };
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return interrupted(operation);
    }

    // A task that fails on an interrupted thread, for example because the connection pool refuses
    // it a connection, failed because of the shutdown.
    if (conclusion == LibraryOperationConclusion.FAILED && Thread.currentThread().isInterrupted()) {
      return interrupted(operation);
    }

    end(operation, conclusion);
    return new LibraryOperationExecutionResult.Ended();
  }

  private LibraryOperationExecutionResult interrupted(LibraryOperation operation) {
    log.info(
        "A shutdown interrupted the {} of library {}; it runs again when a scheduler starts.",
        operation.kind().tagValue(),
        operation.libraryId());
    metrics.countExecution(operation.kind(), ExecutionResult.INTERRUPTED);
    return new LibraryOperationExecutionResult.Interrupted();
  }

  private LibraryOperationConclusion scan(Library library) throws InterruptedException {
    log.info("Starting {} library scan.", library.getName());
    try {
      libraryFolderCheck.requirePassed(library);
      var results = fileDiscoveryRuns.awaitResults(libraryFileWalk.discoverFiles(library));
      // A share can unmount while the scan waits for artwork, and completing the scan removes
      // every item whose file Streamarr cannot find.
      libraryFolderCheck.requirePassed(library);
      eventPublisher.publishEvent(new ScanCompletedEvent(library.getId()));
      log.info("Finished {} library scan: {}.", library.getName(), results.describe());
      return LibraryOperationConclusion.FINISHED;
    } catch (LibraryScanFailedException e) {
      log.error("Failed {} library scan.", library.getName(), e);
      return LibraryOperationConclusion.FAILED;
    }
  }

  private LibraryOperationConclusion refresh(Library library, ImageRefreshMode imageRefreshMode)
      throws InterruptedException {
    log.info("Starting {} library refresh.", library.getName());
    try {
      libraryRefreshService.refreshLibrary(library, imageRefreshMode);
      log.info("Finished {} library refresh.", library.getName());
      return LibraryOperationConclusion.FINISHED;
    } catch (RuntimeException e) {
      log.error("Refresh failed for library '{}'", library.getName(), e);
      return LibraryOperationConclusion.FAILED;
    }
  }

  private void end(LibraryOperation operation, LibraryOperationConclusion conclusion) {
    var end =
        LibraryOperationEnd.builder()
            .libraryOperationId(operation.id())
            .conclusion(conclusion)
            .endedAt(clock.instant())
            .build();
    boolean ended;
    try {
      ended =
          Boolean.TRUE.equals(
              new TransactionTemplate(transactionManager).execute(_ -> tryEnd(operation, end)));
    } catch (RuntimeException e) {
      metrics.countExecution(operation.kind(), ExecutionResult.END_FAILED);
      throw e;
    }

    metrics.countExecution(operation.kind(), ExecutionResult.ENDED);
    if (ended) {
      metrics.timeOperation(
          operation.kind(), conclusion, Duration.between(operation.createdOn(), end.endedAt()));
    }
  }

  private boolean tryEnd(LibraryOperation operation, LibraryOperationEnd end) {
    if (!libraryOperations.tryEndLibraryOperation(end)) {
      return false;
    }

    var libraryId = operation.libraryId();
    eventPublisher.publishEvent(new LibraryStatusChangedEvent(libraryId));
    switch (operation.kind()) {
      case SCAN -> eventPublisher.publishEvent(new ScanEndedEvent(libraryId));
      case REFRESH -> eventPublisher.publishEvent(new RefreshEndedEvent(libraryId));
    }

    return true;
  }
}
