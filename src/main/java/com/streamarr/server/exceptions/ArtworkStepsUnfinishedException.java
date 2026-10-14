package com.streamarr.server.exceptions;

import java.time.Duration;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * An attempt at an item's artwork steps left a step to retry: a retryable failure, or a result that
 * could not be stored. Failing the scheduled task's execution counts the failure for its backoff.
 */
public class ArtworkStepsUnfinishedException extends RuntimeException {

  @Getter private final long generation;
  private final Duration retryAfter;

  public ArtworkStepsUnfinishedException(long generation, @NonNull Optional<Duration> retryAfter) {
    super("Artwork steps of generation %d stay pending".formatted(generation));
    this.generation = generation;
    this.retryAfter = retryAfter.orElse(null);
  }

  /** The delay that the image host asked for before the next attempt, when it asked for one. */
  public Optional<Duration> getRetryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
