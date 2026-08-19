package com.streamarr.server.exceptions;

public class InvalidProfilePinException extends RuntimeException {

  public InvalidProfilePinException() {
    super("The PIN is incorrect.");
  }
}
