package com.streamarr.server.exceptions;

public class ProfileManagementDeniedException extends RuntimeException {

  public ProfileManagementDeniedException() {
    super("The account does not manage the requested profile.");
  }
}
