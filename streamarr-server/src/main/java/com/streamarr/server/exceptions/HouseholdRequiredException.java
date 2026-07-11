package com.streamarr.server.exceptions;

public class HouseholdRequiredException extends RuntimeException {

  public HouseholdRequiredException() {
    super("A household must be selected for this operation.");
  }
}
