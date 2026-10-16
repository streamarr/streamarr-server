package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import lombok.NonNull;

/** Why a library refused to start a scan or a refresh, or refused removeLibrary. */
public sealed interface LibraryOperationRejection {

  record LibraryNotFound() implements LibraryOperationRejection {}

  /**
   * @param status the status the library shows
   * @param runningKind the kind of the operation that runs
   */
  record LibraryBusy(@NonNull LibraryStatus status, @NonNull LibraryOperationKind runningKind)
      implements LibraryOperationRejection {}
}
