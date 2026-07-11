package com.streamarr.server.exceptions;

public class HouseholdAccessDeniedException extends RuntimeException {

  public HouseholdAccessDeniedException() {
    super("The requested household is not accessible to this account.");
  }
}
