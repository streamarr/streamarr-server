package com.streamarr.server.exceptions;

public class AuthenticationRequiredException extends RuntimeException {

  public AuthenticationRequiredException() {
    super("Authentication is required.");
  }
}
