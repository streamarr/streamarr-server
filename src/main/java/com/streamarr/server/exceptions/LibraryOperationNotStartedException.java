package com.streamarr.server.exceptions;

/**
 * The transaction that would start a library operation failed, so the library stays as it was and
 * no operation exists. The client may retry the request.
 */
public class LibraryOperationNotStartedException extends RuntimeException {

  public LibraryOperationNotStartedException(Throwable cause) {
    super("No library scan or refresh started. Try again shortly.", cause);
  }
}
