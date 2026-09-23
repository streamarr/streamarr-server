package com.streamarr.server.exceptions;

public class LibraryRefreshFailedException extends RuntimeException {

  public LibraryRefreshFailedException(String libraryName, Throwable cause) {
    super("Failed to refresh library: " + libraryName, cause);
  }
}
