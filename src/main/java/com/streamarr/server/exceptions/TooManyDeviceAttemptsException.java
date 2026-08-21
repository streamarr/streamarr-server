package com.streamarr.server.exceptions;

import java.time.Duration;
import java.util.Objects;
import lombok.Getter;

@Getter
public class TooManyDeviceAttemptsException extends RuntimeException implements RetryAfterAware {

  private final transient Duration retryAfter;

  public TooManyDeviceAttemptsException(Duration retryAfter) {
    super("Too many attempts; try again later.");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  @Override
  public Duration retryAfter() {
    return retryAfter;
  }
}
