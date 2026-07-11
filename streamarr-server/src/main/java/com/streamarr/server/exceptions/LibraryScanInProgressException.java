package com.streamarr.server.exceptions;

import java.util.UUID;

public class LibraryScanInProgressException extends RuntimeException {

  public LibraryScanInProgressException(UUID libraryId) {
    super("Library is currently being scanned: " + libraryId);
  }
}
