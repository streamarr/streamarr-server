package com.streamarr.server.exceptions;

public class LibraryAccessDeniedException extends RuntimeException {

  public LibraryAccessDeniedException(String filepath) {
    super("Access denied to library path: " + filepath);
  }
}
