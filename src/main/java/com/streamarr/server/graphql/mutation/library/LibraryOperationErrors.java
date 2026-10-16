package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.LibraryOperationStopRejection;

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

  public static StopLibraryScanError toStopLibraryScanError(
      LibraryOperationStopRejection rejection) {
    return switch (rejection) {
      case LibraryOperationStopRejection.LibraryNotFound _ -> libraryNotFound();
      case LibraryOperationStopRejection.NotInProgress notInProgress ->
          new LibraryScanNotInProgressError(
              notInProgressMessage(LibraryOperationKind.SCAN, notInProgress),
              notInProgress.status());
    };
  }

  public static StopLibraryRefreshError toStopLibraryRefreshError(
      LibraryOperationStopRejection rejection) {
    return switch (rejection) {
      case LibraryOperationStopRejection.LibraryNotFound _ -> libraryNotFound();
      case LibraryOperationStopRejection.NotInProgress notInProgress ->
          new LibraryRefreshNotInProgressError(
              notInProgressMessage(LibraryOperationKind.REFRESH, notInProgress),
              notInProgress.status());
    };
  }

  public static InvalidIdError invalidLibraryId() {
    return new InvalidIdError("Enter a valid ID.", InputPath.of(LIBRARY_ID));
  }

  private static LibraryNotFoundError libraryNotFound() {
    return new LibraryNotFoundError("That library does not exist.", InputPath.of(LIBRARY_ID));
  }

  private static LibraryBusyError libraryBusy(LibraryOperationRejection.LibraryBusy busy) {
    var kind = name(busy.runningKind());
    var message = "A %s is already in progress.".formatted(kind);
    if (busy.status() == LibraryStatus.STOPPING) {
      message = "A %s is stopping.".formatted(kind);
    }

    return new LibraryBusyError(message, busy.status());
  }

  private static String notInProgressMessage(
      LibraryOperationKind requestedKind, LibraryOperationStopRejection.NotInProgress rejection) {
    var message =
        "No %s is in progress, so there is nothing to stop.".formatted(name(requestedKind));
    return rejection
        .runningKind()
        .map(runningKind -> message + " " + runningOperation(runningKind, rejection.status()))
        .orElse(message);
  }

  private static String runningOperation(LibraryOperationKind kind, LibraryStatus status) {
    if (status == LibraryStatus.STOPPING) {
      return "A %s is stopping.".formatted(name(kind));
    }

    return "A %s is in progress.".formatted(name(kind));
  }

  private static String name(LibraryOperationKind kind) {
    return switch (kind) {
      case SCAN -> "library scan";
      case REFRESH -> "library refresh";
    };
  }
}
