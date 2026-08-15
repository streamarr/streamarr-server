package com.streamarr.server.exceptions;

public class ProfileDeletionBlockedException extends RuntimeException {

  /**
   * Creates an exception with the specified message.
   *
   * @param message the detail message
   */
  public ProfileDeletionBlockedException(String message) {
    super(message);
  }
}
