package com.streamarr.server.domain.media;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One attempt at a target's artwork steps, for one generation of the target's artwork request.
 *
 * @param libraryOperationId the library operation that waited for the request when the attempt
 *     started, if any
 */
@Builder
public record ArtworkStepsAttempt(
    @NonNull ArtworkTarget target,
    long generation,
    @NonNull Instant attemptedAt,
    Optional<UUID> libraryOperationId) {

  public ArtworkStepsAttempt {
    libraryOperationId = Objects.requireNonNullElse(libraryOperationId, Optional.empty());
  }
}
