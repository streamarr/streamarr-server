package com.streamarr.server.exceptions;

/** The Profile lacks the PIN this Household's safety rule requires; it stays visible but locked. */
public class ProfileLockedException extends RuntimeException {

  public ProfileLockedException() {
    super("This profile needs a PIN before it can be selected here.");
  }
}
