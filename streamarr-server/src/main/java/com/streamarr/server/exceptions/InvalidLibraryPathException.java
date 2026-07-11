package com.streamarr.server.exceptions;

public class InvalidLibraryPathException extends RuntimeException {

  public InvalidLibraryPathException(String filepath, String reason) {
    super("Invalid library path '" + filepath + "': " + reason);
  }
}
