package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.library.LibraryOperationRejection;

/** The exhaustive mapping from a library's refusal to the schema error types. */
public final class LibraryOperationErrors {

  private static final String LIBRARY_ID = "libraryId";

  private LibraryOperationErrors() {}

  public static StartLibraryScanError toStartLibraryScanError(LibraryOperationRejection rejection) {
    return switch (rejection) {
      case LibraryOperationRejection.LibraryNotFound _ -> libraryNotFound();
      case LibraryOperationRejection.LibraryBusy busy -> libraryBusy(busy);
    };
  }

  public static StartLibraryRefreshError toStartLibraryRefreshError(
      LibraryOperationRejection rejection) {
    return switch (rejection) {
      case LibraryOperationRejection.LibraryNotFound _ -> libraryNotFound();
      case LibraryOperationRejection.LibraryBusy busy -> libraryBusy(busy);
    };
  }

  public static RemoveLibraryError toRemoveLibraryError(LibraryOperationRejection rejection) {
    return switch (rejection) {
      case LibraryOperationRejection.LibraryNotFound _ -> libraryNotFound();
      case LibraryOperationRejection.LibraryBusy busy -> libraryBusy(busy);
    };
  }

  public static InvalidIdError invalidLibraryId() {
    return new InvalidIdError("Enter a valid ID.", InputPath.of(LIBRARY_ID));
  }

  private static LibraryNotFoundError libraryNotFound() {
    return new LibraryNotFoundError("That library does not exist.", InputPath.of(LIBRARY_ID));
  }

  private static LibraryBusyError libraryBusy(LibraryOperationRejection.LibraryBusy busy) {
    var message =
        switch (busy.runningKind()) {
          case SCAN -> "A library scan is already in progress.";
          case REFRESH -> "A library refresh is already in progress.";
        };
    return new LibraryBusyError(message, busy.status());
  }
}
