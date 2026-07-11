package com.streamarr.server.exceptions;

public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("Invalid email or password.");
  }
}
