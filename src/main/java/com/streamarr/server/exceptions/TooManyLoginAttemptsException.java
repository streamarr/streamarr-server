package com.streamarr.server.exceptions;

import java.time.Duration;

public class TooManyLoginAttemptsException extends TooManyAttemptsException {

  public TooManyLoginAttemptsException(Duration retryAfter) {
    super("Too many failed login attempts. Try again later.", retryAfter);
  }
}
