package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import java.util.Optional;
import lombok.NonNull;

/** Why a library refused to stop a scan or a refresh. */
public sealed interface LibraryOperationStopRejection {

  record LibraryNotFound() implements LibraryOperationStopRejection {}

  /**
   * No operation of the requested kind runs, so the stop has nothing to stop.
   *
   * @param status the status the library shows
   * @param runningKind the kind of the operation that runs instead, if any
   */
  record NotInProgress(
      @NonNull LibraryStatus status, @NonNull Optional<LibraryOperationKind> runningKind)
      implements LibraryOperationStopRejection {}
}
