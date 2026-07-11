package com.streamarr.server.exceptions;

import java.util.UUID;

public class SeriesNotFoundException extends RuntimeException {

  public SeriesNotFoundException(UUID seriesId, UUID seasonId) {
    super("Series not found: " + seriesId + " (referenced by season " + seasonId + ")");
  }
}
