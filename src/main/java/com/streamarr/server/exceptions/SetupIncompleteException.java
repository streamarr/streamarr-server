package com.streamarr.server.exceptions;

public class SetupIncompleteException extends RuntimeException {

  public SetupIncompleteException() {
    super("The server has not completed initial setup.");
  }
}
