package com.streamarr.server.exceptions;

import java.time.Duration;

public class TooManyDeviceAttemptsException extends TooManyAttemptsException {

  public TooManyDeviceAttemptsException(Duration retryAfter) {
    super("Too many attempts; try again later.", retryAfter);
  }
}
