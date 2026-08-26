package com.streamarr.server.exceptions;

import java.time.Duration;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class CredentialAttemptRejectedException extends RuntimeException {

  private final Duration retryAfter;

  public CredentialAttemptRejectedException(@NonNull Duration retryAfter) {
    super("Credential verification is temporarily throttled");
    this.retryAfter = retryAfter;
  }
}
