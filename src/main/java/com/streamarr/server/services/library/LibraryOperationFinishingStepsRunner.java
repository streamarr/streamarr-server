package com.streamarr.server.services.library;

import com.streamarr.server.exceptions.LibraryNotFoundException;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the finishing steps of a library operation, in order, before the transaction that ends the
 * operation. The operation's kind and conclusion select the steps. A finished scan checks the
 * library folder and then removes the media files that no longer exist on disk. A failed scan and
 * every refresh run no step here. The transaction that ends the operation recalculates the letter
 * index, the last step of every operation.
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
  private final LibraryFolderCheck libraryFolderCheck;
  private final OrphanedMediaFileCleanupService orphanedMediaFileCleanupService;
  private final PlatformTransactionManager transactionManager;

  /**
   * Runs the steps that the operation's kind and conclusion select. A failed step leaves the
   * library as it was before the step, and the operation still ends.
   *
   * @return the conclusion that the operation ends with: {@link LibraryOperationConclusion#FAILED}
   *     when the step of a finished scan fails before the library folder passed its check, and
   *     otherwise the given conclusion
   */
  public LibraryOperationConclusion runFinishingSteps(
      LibraryOperation operation, LibraryOperationConclusion conclusion) {
    if (!removesMissingMediaFiles(operation, conclusion)) {
      return conclusion;
    }

    var libraryFolderPassed = new AtomicBoolean();
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(_ -> removeMissingMediaFiles(operation, libraryFolderPassed));
    } catch (RuntimeException e) {
      // Only a removal that fails after the library folder passed its check keeps the scan's
      // conclusion. Any earlier failure, including a check that cannot finish or a rollback that
      // replaces the check's exception, leaves the folder unchecked and fails the scan.
      if (libraryFolderPassed.get()) {
        log.error(
            "Failed to remove missing media files at the end of the scan of library {}.",
            operation.libraryId(),
            e);
        return conclusion;
      }

      log.error(
          "Removed no missing media files at the end of the scan of library {}, because its"
              + " library folder did not pass its check.",
          operation.libraryId(),
          e);
      return LibraryOperationConclusion.FAILED;
    }

    return conclusion;
  }

  private static boolean removesMissingMediaFiles(
      LibraryOperation operation, LibraryOperationConclusion conclusion) {
    return switch (operation.kind()) {
      case SCAN -> conclusion == LibraryOperationConclusion.FINISHED;
      case REFRESH -> false;
    };
  }

  private void removeMissingMediaFiles(
      LibraryOperation operation, AtomicBoolean libraryFolderPassed) {
    if (!libraryOperations.tryLockLibraryOperation(operation.id())) {
      return;
    }

    // The shared row of the operation keeps its library from being deleted.
    var library =
        libraryRepository
            .findById(operation.libraryId())
            .orElseThrow(() -> new LibraryNotFoundException(operation.libraryId()));
    // A share can unmount while the scan waits for artwork or this step waits for the operation's
    // row, and an unmounted share looks like a library whose every media file is missing.
    libraryFolderCheck.requirePassed(library);
    libraryFolderPassed.set(true);
    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);
  }
}
