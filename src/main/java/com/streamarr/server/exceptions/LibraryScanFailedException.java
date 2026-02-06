package com.streamarr.server.exceptions;

public class LibraryScanFailedException extends RuntimeException {

  public LibraryScanFailedException(String libraryName, Throwable cause) {
    super("Failed to scan library: " + libraryName, cause);
  }
}
