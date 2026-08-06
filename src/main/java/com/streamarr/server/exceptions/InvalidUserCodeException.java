package com.streamarr.server.exceptions;

public class InvalidUserCodeException extends RuntimeException {

  public InvalidUserCodeException() {
    super("The user code is not a valid pairing code.");
  }
}
