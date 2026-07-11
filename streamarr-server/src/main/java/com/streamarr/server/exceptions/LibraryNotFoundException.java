package com.streamarr.server.exceptions;

import java.util.UUID;

public class LibraryNotFoundException extends RuntimeException {

  public LibraryNotFoundException(UUID libraryId) {
    super("Library not found: " + libraryId);
  }
}
