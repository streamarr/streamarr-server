package com.streamarr.server.exceptions;

public class ServerAdministrationDeniedException extends RuntimeException {

  /**
   * Creates an exception indicating that live ServerAdmin authority is required.
   */
  public ServerAdministrationDeniedException() {
    super("Live ServerAdmin authority is required for this operation.");
  }
}
