package com.streamarr.server.exceptions;

public class ProfileRehomeFailedException extends RuntimeException {

  public ProfileRehomeFailedException() {
    super("The Personal Profile could not move to the requested Household.");
  }
}
