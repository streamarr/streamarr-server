package com.streamarr.server.exceptions;

public class InvalidRefreshTokenException extends RuntimeException {

  public InvalidRefreshTokenException() {
    super("The refresh token is unknown or expired.");
  }
}
