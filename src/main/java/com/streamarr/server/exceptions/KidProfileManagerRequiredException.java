package com.streamarr.server.exceptions;

public class KidProfileManagerRequiredException extends RuntimeException {

  public KidProfileManagerRequiredException() {
    super("A kid profile requires a local owner or parent to accept profile management.");
  }
}
