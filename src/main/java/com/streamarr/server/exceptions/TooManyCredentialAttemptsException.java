package com.streamarr.server.exceptions;

public class TooManyCredentialAttemptsException extends RuntimeException {

  public TooManyCredentialAttemptsException() {
    super("Too many failed credential attempts. Try again later.");
  }
}
