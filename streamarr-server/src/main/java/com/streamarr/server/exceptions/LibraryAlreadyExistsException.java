package com.streamarr.server.exceptions;

public class LibraryAlreadyExistsException extends RuntimeException {

  public LibraryAlreadyExistsException(String filepath) {
    super("Library already exists at filepath: " + filepath);
  }
}
