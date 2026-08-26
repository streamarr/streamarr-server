package com.streamarr.server.exceptions;

public class InvalidProfilePinException extends CredentialVerificationException {

  public InvalidProfilePinException() {
    super("The PIN is incorrect.");
  }
}
