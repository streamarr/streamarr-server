package com.streamarr.server.services.probe;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

/** Retries a failed execution after 5s, doubling per consecutive failure up to 300s. */
@RequiredArgsConstructor
public class CappedExponentialBackoff<T> implements FailureHandler<T> {

  private static final Duration INITIAL_DELAY = Duration.ofSeconds(5);
  private static final Duration MAX_DELAY = Duration.ofSeconds(300);
  private static final int MAX_DOUBLINGS = 6;

  private final Clock clock;

  @Override
  public void onFailure(
      ExecutionComplete executionComplete, ExecutionOperations<T> executionOperations) {
    var delay = delayAfter(executionComplete.getExecution().consecutiveFailures);
    executionOperations.reschedule(executionComplete, clock.instant().plus(delay));
  }

  static Duration delayAfter(int previousFailures) {
    var doubled = INITIAL_DELAY.multipliedBy(1L << Math.min(previousFailures, MAX_DOUBLINGS));
    return doubled.compareTo(MAX_DELAY) < 0 ? doubled : MAX_DELAY;
  }
}
