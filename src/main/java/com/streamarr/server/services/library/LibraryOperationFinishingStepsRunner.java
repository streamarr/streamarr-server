package com.streamarr.server.services.library;

import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the finishing steps of a library operation, in order, before the transaction that ends the
 * operation. The operation's kind and conclusion select the steps. A finished scan removes the
 * media files that no longer exist on disk. A failed scan and every refresh run no step here. The
 * transaction that ends the operation recalculates the letter index, the last step of every
 * operation.
 *
 * <p>Each step runs in its own transaction, which first shares the operation's row. A step for an
 * operation that has ended does nothing. A step that a restart interrupted has the same effect when
 * it runs again.
 */
@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class LibraryOperationFinishingStepsRunner {

  private final LibraryOperationRepository libraryOperations;
  private final LibraryRepository libraryRepository;
  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService;
  private final PlatformTransactionManager transactionManager;

  /**
   * Runs the steps that the operation's kind and conclusion select. A failed step leaves the
   * library as it was before the step, and the operation still ends.
   */
  public void runFinishingSteps(LibraryOperation operation, LibraryOperationConclusion conclusion) {
    if (!removesMissingMediaFiles(operation, conclusion)) {
      return;
    }

    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(_ -> removeMissingMediaFiles(operation));
    } catch (RuntimeException e) {
      log.error(
          "Failed to remove missing media files at the end of the {} of library {}.",
          operation.kind().tagValue(),
          operation.libraryId(),
          e);
    }
  }

  private static boolean removesMissingMediaFiles(
      LibraryOperation operation, LibraryOperationConclusion conclusion) {
    return switch (operation.kind()) {
      case SCAN -> conclusion == LibraryOperationConclusion.FINISHED;
      case REFRESH -> false;
    };
  }

  private void removeMissingMediaFiles(LibraryOperation operation) {
    if (!libraryOperations.tryLockLibraryOperation(operation.id())) {
      return;
    }

    libraryRepository
        .findById(operation.libraryId())
        .ifPresent(orphanedMediaFileCleanupService::cleanupOrphanedFiles);
  }
}
