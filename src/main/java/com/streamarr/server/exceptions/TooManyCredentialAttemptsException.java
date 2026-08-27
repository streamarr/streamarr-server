package com.streamarr.server.exceptions;

import java.time.Duration;

public class TooManyCredentialAttemptsException extends TooManyAttemptsException {

  public TooManyCredentialAttemptsException(Duration retryAfter) {
    super("Too many failed credential attempts. Try again later.", retryAfter);
  }
}
