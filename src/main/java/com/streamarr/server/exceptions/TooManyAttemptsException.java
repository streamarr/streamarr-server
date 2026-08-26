package com.streamarr.server.exceptions;

import java.time.Duration;
import lombok.NonNull;

/** A throttled request; each subclass keeps its own transport wording and error code. */
public abstract class TooManyAttemptsException extends RuntimeException implements RetryAfterAware {

  private final transient Duration retryAfter;

  protected TooManyAttemptsException(String message, @NonNull Duration retryAfter) {
    super(message);
    this.retryAfter = retryAfter;
  }

  @Override
  public Duration retryAfter() {
    return retryAfter;
  }
}
