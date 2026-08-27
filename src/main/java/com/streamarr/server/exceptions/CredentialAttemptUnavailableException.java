package com.streamarr.server.exceptions;

public class CredentialAttemptUnavailableException extends RuntimeException {

  public CredentialAttemptUnavailableException(Throwable cause) {
    super("Credential verification is temporarily unavailable.", cause);
  }
}
