package com.streamarr.server.exceptions;

public class ServerAdministrationDeniedException extends RuntimeException {

  public ServerAdministrationDeniedException() {
    super("Live ServerAdmin authority is required for this operation.");
  }
}
