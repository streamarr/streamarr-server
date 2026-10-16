package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.exceptions.LibraryNotFoundException;
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
   *     when the library folder does not pass its check before the removal of missing media files,
   *     including when the check cannot finish, and otherwise the given conclusion
   */
  public LibraryOperationConclusion runFinishingSteps(
      LibraryOperation operation, LibraryOperationConclusion conclusion) {
    if (!removesMissingMediaFiles(operation, conclusion)) {
      return conclusion;
    }

    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(_ -> removeMissingMediaFiles(operation));
    } catch (LibraryFolderNotPassedException e) {
      log.error(
          "Removed no missing media files at the end of the scan of library {}.",
          operation.libraryId(),
          e.getCause());
      return LibraryOperationConclusion.FAILED;
    } catch (RuntimeException e) {
      log.error(
          "Failed to remove missing media files at the end of the {} of library {}.",
          operation.kind().tagValue(),
          operation.libraryId(),
          e);
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

  private void removeMissingMediaFiles(LibraryOperation operation) {
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
    requireLibraryFolderPassed(library);
    orphanedMediaFileCleanupService.cleanupOrphanedFiles(library);
  }

  // A check that cannot finish, for example because the database is unavailable, does not pass
  // either, so it fails the scan like a failed check rather than counting as a failed removal.
  private void requireLibraryFolderPassed(Library library) {
    try {
      libraryFolderCheck.requirePassed(library);
    } catch (RuntimeException e) {
      throw new LibraryFolderNotPassedException(e);
    }
  }

  private static final class LibraryFolderNotPassedException extends RuntimeException {

    private LibraryFolderNotPassedException(RuntimeException cause) {
      super(cause);
    }
  }
}
