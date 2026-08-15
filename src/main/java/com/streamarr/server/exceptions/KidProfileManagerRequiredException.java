package com.streamarr.server.exceptions;

public class KidProfileManagerRequiredException extends RuntimeException {

  /**
   * Creates an exception indicating that a kid profile requires a local owner or parent to accept profile management.
   */
  public KidProfileManagerRequiredException() {
    super("A kid profile requires a local owner or parent to accept profile management.");
  }
}
