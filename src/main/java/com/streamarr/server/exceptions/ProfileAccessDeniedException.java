package com.streamarr.server.exceptions;

public class ProfileAccessDeniedException extends RuntimeException {

  public ProfileAccessDeniedException() {
    super("The requested profile is not accessible to this account.");
  }
}
