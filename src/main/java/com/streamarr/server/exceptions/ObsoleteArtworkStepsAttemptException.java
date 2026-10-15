package com.streamarr.server.exceptions;

import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import lombok.Getter;
import lombok.NonNull;

/**
 * An artwork steps attempt tried to save images after a newer artwork request replaced its
 * generation, or after its target was deleted. The attempt saves nothing and records no result.
 */
@Getter
public class ObsoleteArtworkStepsAttemptException extends RuntimeException {

  private final transient ArtworkStepsAttempt attempt;

  public ObsoleteArtworkStepsAttemptException(@NonNull ArtworkStepsAttempt attempt) {
    super(
        "Artwork generation %d of %s %s is no longer requested"
            .formatted(
                attempt.generation(), attempt.target().targetType(), attempt.target().targetId()));
    this.attempt = attempt;
  }
}
