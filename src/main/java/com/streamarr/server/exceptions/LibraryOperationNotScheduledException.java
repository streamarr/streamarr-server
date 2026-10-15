package com.streamarr.server.exceptions;

import java.util.UUID;

/** The scheduler could not store the task that executes a library operation. */
public class LibraryOperationNotScheduledException extends RuntimeException {

  public LibraryOperationNotScheduledException(UUID libraryOperationId, Throwable cause) {
    super("Could not schedule library operation " + libraryOperationId, cause);
  }
}
