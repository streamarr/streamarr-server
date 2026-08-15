package com.streamarr.server.exceptions;

public class ProfileManagementDeniedException extends RuntimeException {

  /**
   * Creates an exception indicating that the account does not manage the requested profile.
   */
  public ProfileManagementDeniedException() {
    super("The account does not manage the requested profile.");
  }
}
