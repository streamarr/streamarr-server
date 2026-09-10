package com.streamarr.server.exceptions;

/**
 * The same response for malformed, unknown, expired, already-used, or mismatched codes. Requests
 * that exceed the credential's attempt limit receive a throttle error instead.
 */
public class InvalidOneTimeCodeException extends CredentialVerificationException {

  public InvalidOneTimeCodeException() {
    super("This code cannot be used.");
  }
}
