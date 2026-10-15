package com.streamarr.server.exceptions;

import com.streamarr.server.domain.media.ArtworkAttempt;
import lombok.Getter;
import lombok.NonNull;

/**
 * An artwork attempt tried to save images after a newer artwork request replaced its generation, or
 * after its target was deleted. The attempt saves nothing and records no result.
 */
@Getter
public class ObsoleteArtworkAttemptException extends RuntimeException {

  private final transient ArtworkAttempt attempt;

  public ObsoleteArtworkAttemptException(@NonNull ArtworkAttempt attempt) {
    super(
        "Artwork generation %d of %s %s is no longer requested"
            .formatted(
                attempt.generation(), attempt.target().targetType(), attempt.target().targetId()));
    this.attempt = attempt;
  }
}
