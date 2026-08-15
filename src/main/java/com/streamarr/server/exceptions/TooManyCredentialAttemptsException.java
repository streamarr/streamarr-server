package com.streamarr.server.exceptions;

public class TooManyCredentialAttemptsException extends RuntimeException {

  /**
   * Creates an exception indicating that credential attempts have exceeded the allowed limit.
   */
  public TooManyCredentialAttemptsException() {
    super("Too many failed credential attempts. Try again later.");
  }
}
