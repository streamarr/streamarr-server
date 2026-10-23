package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.library.LibraryOperationMetrics.ExecutionResult;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One execution of a stored library operation, independent of the scheduler that runs the
 * execution. Every execution walks the library from the start, unless a ServerAdmin's stop is
 * stored with the operation. The walk admits no further task once the stop is stored or the
 * scheduler shuts down, and the tasks it admitted finish their foreground steps (ADR 0027). A
 * shutdown leaves the operation stored, so the operation runs again when a scheduler starts. An
 * execution that finds the stop stored when it starts admits no task and waits only for the primary
 * artwork that earlier executions of the operation requested. Otherwise the finishing steps run,
 * and then one transaction ends the operation: as failed when the scan or refresh threw or the
 * library folder failed its check before a finishing step, as stopped when the stop is stored, and
 * as finished otherwise. The transaction locks the library's row, gives the library its final
 * status and deletes the operation. It also publishes the status change and the end of the scan or
 * refresh for listeners that run after the commit. An end that cannot be saved escapes, so the
 * scheduler runs the operation again with backoff.
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
  private final LibraryOperationFinishingStepsRunner finishingStepsRunner;
  private final ApplicationEventPublisher eventPublisher;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;
  private final LibraryOperationMetrics metrics;

  /**
   * Runs the stored operation once.
   *
   * @param shuttingDown reads whether the scheduler that runs the execution shuts down, which
   *     drains the execution: it admits no further task and leaves the operation stored
   * @return {@link LibraryOperationExecutionResult.Ended} when the operation ended or no longer
   *     exists, or {@link LibraryOperationExecutionResult.Interrupted} when a shutdown drained or
   *     interrupted the execution. The interrupt flag is then set if a shutdown interrupted it.
   */
  public LibraryOperationExecutionResult execute(
      UUID libraryOperationId, BooleanSupplier shuttingDown) {
    return libraryOperations
        .findLibraryOperation(libraryOperationId)
        .map(operation -> execute(operation, shuttingDown))
        .orElseGet(LibraryOperationExecutionResult.Ended::new);
  }

  private LibraryOperationExecutionResult execute(
      LibraryOperation operation, BooleanSupplier shuttingDown) {
    var library =
        libraryRepository
            .findById(operation.libraryId())
            .orElseThrow(() -> new LibraryNotFoundException(operation.libraryId()));
    AdmissionEnd admissionEnd =
        () -> shuttingDown.getAsBoolean() || libraryOperations.hasStoredStop(operation.id());
    LibraryOperationConclusion conclusion;
    try {
      conclusion =
          switch (operation.kind()) {
            case SCAN -> scan(library, operation, admissionEnd);
            case REFRESH -> refresh(library, operation, admissionEnd);
          };
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return interrupted(operation);
    }

    // A task that fails on an interrupted thread, for example because the connection pool refuses
    // it a connection, failed because of the shutdown. A drained walk admitted only some tasks.
    if (shuttingDown.getAsBoolean()
        || conclusion == LibraryOperationConclusion.FAILED
            && Thread.currentThread().isInterrupted()) {
      return interrupted(operation);
    }

    if (libraryOperations.hasStoredStop(operation.id())) {
      conclusion = conclusion.afterStop();
    }

    conclusion = finishingStepsRunner.runFinishingSteps(operation, conclusion);
    // A finishing step that a shutdown interrupted runs again with the operation.
    if (Thread.currentThread().isInterrupted()) {
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

  private LibraryOperationConclusion scan(
      Library library, LibraryOperation operation, AdmissionEnd admissionEnd)
      throws InterruptedException {
    log.info("Starting {} library scan.", library.getName());
    try {
      var results =
          fileDiscoveryRuns.awaitResults(
              discoverFilesUnlessStopped(library, operation, admissionEnd), admissionEnd);
      log.info("Finished {} library scan: {}.", library.getName(), results.describe());
      return LibraryOperationConclusion.FINISHED;
    } catch (RuntimeException e) {
      log.error("Failed {} library scan.", library.getName(), e);
      return LibraryOperationConclusion.FAILED;
    }
  }

  // An execution that finds the stop stored admits no task, so it neither checks nor walks the
  // library folder. Its closed discovery waits only for the primary artwork of the operation.
  private FileDiscovery discoverFilesUnlessStopped(
      Library library, LibraryOperation operation, AdmissionEnd admissionEnd)
      throws InterruptedException {
    if (operation.stopRequested()) {
      try (var discovery = fileDiscoveryRuns.openScan(library, operation.id())) {
        return discovery;
      }
    }

    libraryFolderCheck.requirePassed(library);
    return libraryFileWalk.discoverFiles(library, operation.id(), admissionEnd);
  }

  private LibraryOperationConclusion refresh(
      Library library, LibraryOperation operation, AdmissionEnd admissionEnd)
      throws InterruptedException {
    log.info("Starting {} library refresh.", library.getName());
    try {
      libraryRefreshService.refreshLibrary(library, operation, admissionEnd);
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
    Optional<LibraryOperationConclusion> ended;
    try {
      ended =
          Optional.ofNullable(
                  new TransactionTemplate(transactionManager).execute(_ -> tryEnd(operation, end)))
              .flatMap(Function.identity());
    } catch (RuntimeException e) {
      metrics.countExecution(operation.kind(), ExecutionResult.END_FAILED);
      throw e;
    }

    metrics.countExecution(operation.kind(), ExecutionResult.ENDED);
    ended.ifPresent(
        endedWith ->
            metrics.timeOperation(
                operation.kind(),
                endedWith,
                Duration.between(operation.createdOn(), end.endedAt())));
  }

  private Optional<LibraryOperationConclusion> tryEnd(
      LibraryOperation operation, LibraryOperationEnd end) {
    var ended = libraryOperations.tryEndLibraryOperation(end);
    if (ended.isEmpty()) {
      return ended;
    }

    var libraryId = operation.libraryId();
    eventPublisher.publishEvent(new LibraryStatusChangedEvent(libraryId));
    eventPublisher.publishEvent(
        switch (operation.kind()) {
          case SCAN -> new ScanEndedEvent(libraryId);
          case REFRESH -> new RefreshEndedEvent(libraryId);
        });
    return ended;
  }
}
