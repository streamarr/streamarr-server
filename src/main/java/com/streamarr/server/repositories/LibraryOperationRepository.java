package com.streamarr.server.repositories;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationEnd;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.NewLibraryOperation;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * Stores, reads and ends library operations. Every writer locks the library's row first and the
 * operation's row second, so the library's status and its operation change together.
 */
public interface LibraryOperationRepository {

  /**
   * Decides on the library's row, in the caller's transaction: an idle library shows the
   * operation's busy status and stores the operation; a busy or missing library is refused.
   *
   * @throws org.springframework.dao.PessimisticLockingFailureException when the library's row stays
   *     locked past the bounded lock wait
   */
  StoreOutcome storeLibraryOperation(NewLibraryOperation newLibraryOperation);

  /**
   * Locks the library's row for removeLibrary in the caller's transaction, so no operation can
   * start before the removal commits.
   *
   * @return the reason the library cannot be removed now, if any
   */
  Optional<LibraryOperationRejection> lockLibraryForLibraryRemoval(UUID libraryId);

  Optional<LibraryOperation> findLibraryOperation(UUID libraryOperationId);

  boolean existsLibraryOperationForLibrary(UUID libraryId);

  /**
   * Deletes the operation and gives the library the conclusion's status in the caller's
   * transaction. A scan also stores the end time as the library's scan completion time.
   *
   * @return false when the operation no longer exists, because another execution ended it
   */
  boolean tryEndLibraryOperation(LibraryOperationEnd end);

  /** What {@link #storeLibraryOperation} decided. */
  sealed interface StoreOutcome {

    /**
     * @param library the library as the storing transaction left it, detached from any persistence
     *     context
     */
    record Stored(@NonNull LibraryOperation libraryOperation, @NonNull Library library)
        implements StoreOutcome {}

    record Rejected(@NonNull LibraryOperationRejection rejection) implements StoreOutcome {}
  }
}
