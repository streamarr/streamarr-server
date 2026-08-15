package com.streamarr.server.exceptions;

public class InvalidProfilePinException extends IllegalArgumentException {

  /**
   * Creates an exception indicating that a profile PIN must contain four to twelve digits.
   */
  public InvalidProfilePinException() {
    super("A profile PIN must contain four to twelve digits.");
  }
}
