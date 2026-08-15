package com.streamarr.server.exceptions;

public class ProfileManagerInvariantException extends RuntimeException {

  /**
   * Creates an exception with the specified detail message.
   *
   * @param message the detail message
   */
  public ProfileManagerInvariantException(String message) {
    super(message);
  }
}
