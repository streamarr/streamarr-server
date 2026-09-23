package com.streamarr.server.exceptions;

import java.util.List;

/** Required artwork finished, but the database did not store some of its results. */
public class ArtworkResultNotRecordedException extends RuntimeException {

  public ArtworkResultNotRecordedException(String runDescription, List<Throwable> failures) {
    super(message(runDescription, failures.size()), failures.getFirst());
    failures.stream().skip(1).forEach(this::addSuppressed);
  }

  private static String message(String runDescription, int failureCount) {
    var requests = "requests";
    if (failureCount == 1) {
      requests = "request";
    }

    return "Could not record the results of %d required artwork %s for %s"
        .formatted(failureCount, requests, runDescription);
  }
}
