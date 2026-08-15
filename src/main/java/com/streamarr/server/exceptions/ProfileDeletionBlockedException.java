package com.streamarr.server.exceptions;

public class ProfileDeletionBlockedException extends RuntimeException {

  public ProfileDeletionBlockedException(String message) {
    super(message);
  }
}
