package com.streamarr.server.exceptions;

public class SetupAlreadyCompletedException extends RuntimeException {

  public SetupAlreadyCompletedException() {
    super("Server setup has already been completed.");
  }
}
