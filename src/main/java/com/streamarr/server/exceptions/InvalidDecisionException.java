package com.streamarr.server.exceptions;

public class InvalidDecisionException extends RuntimeException {

  public InvalidDecisionException() {
    super("The decision must be APPROVE or DENY.");
  }
}
