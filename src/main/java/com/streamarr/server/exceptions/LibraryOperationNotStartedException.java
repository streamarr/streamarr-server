package com.streamarr.server.exceptions;

/**
 * The transaction that would start a library operation failed, so the library stays as it was and
 * no operation exists. The client may retry the request.
 */
public class LibraryOperationNotStartedException extends RuntimeException {

  public LibraryOperationNotStartedException(Throwable cause) {
    super("The library operation could not be started; try again shortly.", cause);
  }
}
