package com.streamarr.server.exceptions;

import java.time.Duration;
import lombok.Getter;

@Getter
public class TooManyDeviceAttemptsException extends RuntimeException {

  private final transient Duration retryAfter;

  public TooManyDeviceAttemptsException(Duration retryAfter) {
    super("Too many attempts; try again later.");
    this.retryAfter = retryAfter;
  }
}
