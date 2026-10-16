package com.streamarr.server.services;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/** How one attempt at an item's artwork steps ended, as its scheduled task acts on it. */
public sealed interface ArtworkStepsOutcome {

  /** The item has no pending artwork request, so no step runs. */
  record NotRequested() implements ArtworkStepsOutcome {}

  /**
   * Every step of the attempted generation has a final result, or a newer generation or a deletion
   * made the attempt obsolete.
   */
  record Finished(long generation) implements ArtworkStepsOutcome {}

  /**
   * A step failed in a way that may pass, or its result could not be stored. The step stays pending
   * and runs again after {@code retryAfter} at the earliest, unless a library operation took over
   * the request after the attempt started.
   *
   * @param libraryOperationId the library operation that waited for the request when the attempt
   *     started, if any
   */
  record Unfinished(
      long generation,
      @NonNull Optional<UUID> libraryOperationId,
      @NonNull Optional<Duration> retryAfter)
      implements ArtworkStepsOutcome {}

  /** The attempt was interrupted, for example because the server is stopping. */
  record Interrupted(long generation) implements ArtworkStepsOutcome {}
}
