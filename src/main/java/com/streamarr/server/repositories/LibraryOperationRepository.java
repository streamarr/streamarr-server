package com.streamarr.server.repositories;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.library.LibraryOperation;
import com.streamarr.server.services.library.LibraryOperationConclusion;
import com.streamarr.server.services.library.LibraryOperationEnd;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.LibraryOperationStop;
import com.streamarr.server.services.library.LibraryOperationStopRejection;
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
   * Stores a ServerAdmin's stop with the running operation of the requested kind and gives the
   * library {@code STOPPING}, in the caller's transaction. A stop that is already stored changes
   * nothing.
   *
   * @throws org.springframework.dao.PessimisticLockingFailureException when the library's row stays
   *     locked past the bounded lock wait
   */
  StopOutcome stopLibraryOperation(LibraryOperationStop stop);

  /**
   * Locks the library's row for removeLibrary in the caller's transaction, so no operation can
   * start before the removal commits.
   *
   * @return the reason the library cannot be removed now, if any
   */
  Optional<LibraryOperationRejection> lockLibraryForLibraryRemoval(UUID libraryId);

  Optional<LibraryOperation> findLibraryOperation(UUID libraryOperationId);

  /**
   * Reads whether a ServerAdmin's stop is stored with the operation, as every execution of the
   * operation does before it admits a task.
   *
   * @return false when no stop is stored or the operation no longer exists
   */
  boolean hasStoredStop(UUID libraryOperationId);

  /**
   * Shares the operation's key in the caller's transaction, as a finishing step does before it
   * changes the library. No end can delete the operation until the transaction ends, and a stop can
   * still be stored meanwhile.
   *
   * @return false when the operation no longer exists, because an execution ended it
   * @throws org.springframework.dao.PessimisticLockingFailureException when the operation's row
   *     stays locked past the bounded lock wait
   */
  boolean tryLockLibraryOperation(UUID libraryOperationId);

  /**
   * Shares the library's row in the caller's transaction and reads whether an operation runs. The
   * share waits behind an end that holds the row, so it reads the operation only after the end
   * commits or rolls back.
   *
   * @return true when the library exists and no operation runs
   * @throws org.springframework.dao.PessimisticLockingFailureException when the library's row stays
   *     locked past the bounded lock wait
   */
  boolean tryLockLibraryWithoutLibraryOperation(UUID libraryId);

  /**
   * Deletes the operation and gives the library the status of the conclusion that the operation
   * ends with, in the caller's transaction. A stop stored with the operation turns a finished
   * operation into a stopped one, which gives back the library's status from before the operation.
   * A scan also stores the end time as the library's scan completion time.
   *
   * @return the conclusion that the operation ended with, or nothing when the operation no longer
   *     exists, because another execution ended it
   */
  Optional<LibraryOperationConclusion> tryEndLibraryOperation(LibraryOperationEnd end);

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

  /** What {@link #stopLibraryOperation} decided. */
  sealed interface StopOutcome {

    /**
     * The request stored the stop.
     *
     * @param library the library as the stopping transaction left it, already {@code STOPPING}
     */
    record Stored(@NonNull Library library) implements StopOutcome {}

    /**
     * An earlier request stored the stop, so the library is unchanged.
     *
     * @param library the library, already {@code STOPPING}
     */
    record AlreadyStored(@NonNull Library library) implements StopOutcome {}

    record Rejected(@NonNull LibraryOperationStopRejection rejection) implements StopOutcome {}
  }
}
