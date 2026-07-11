package com.streamarr.server.exceptions;

public class LibraryPathPermissionDeniedException extends RuntimeException {

  public LibraryPathPermissionDeniedException(String filepath) {
    super("Filesystem permission denied for library path: " + filepath);
  }
}
