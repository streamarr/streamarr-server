package com.streamarr.server.exceptions;

public class InvalidCredentialsException extends CredentialVerificationException {

  public InvalidCredentialsException() {
    super("Invalid email or password.");
  }
}
