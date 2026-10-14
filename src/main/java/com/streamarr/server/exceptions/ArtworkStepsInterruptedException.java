package com.streamarr.server.exceptions;

import lombok.Getter;

/** An attempt at an item's artwork steps was interrupted, for example by a stopping server. */
@Getter
public class ArtworkStepsInterruptedException extends RuntimeException {

  private final long generation;

  public ArtworkStepsInterruptedException(long generation) {
    super("Artwork steps of generation %d were interrupted".formatted(generation));
    this.generation = generation;
  }
}
