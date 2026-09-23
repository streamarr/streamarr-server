package com.streamarr.server.exceptions;

import java.util.List;

/** Required artwork finished, but the database did not store some of its results. */
public class ArtworkResultNotRecordedException extends RuntimeException {

  public ArtworkResultNotRecordedException(String runDescription, List<Throwable> failures) {
    super(
        "Could not record the results of %d required artwork %s for %s"
            .formatted(
                failures.size(), failures.size() == 1 ? "request" : "requests", runDescription),
        failures.getFirst());
    failures.stream().skip(1).forEach(this::addSuppressed);
  }
}
