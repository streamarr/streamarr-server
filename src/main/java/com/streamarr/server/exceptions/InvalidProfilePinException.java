package com.streamarr.server.exceptions;

public class InvalidProfilePinException extends IllegalArgumentException {

  public InvalidProfilePinException() {
    super("A profile PIN must contain four to twelve digits.");
  }
}
