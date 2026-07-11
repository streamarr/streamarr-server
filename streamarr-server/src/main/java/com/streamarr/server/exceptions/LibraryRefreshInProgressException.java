package com.streamarr.server.exceptions;

import java.util.UUID;

public class LibraryRefreshInProgressException extends RuntimeException {

  public LibraryRefreshInProgressException(UUID libraryId) {
    super("Library is currently being refreshed: " + libraryId);
  }
}
