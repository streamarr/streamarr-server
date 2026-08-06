package com.streamarr.server.repositories.auth;

public class UserCodeCollisionException extends RuntimeException {

  public UserCodeCollisionException(Throwable cause) {
    super("A device authorization already uses that user code.", cause);
  }
}
