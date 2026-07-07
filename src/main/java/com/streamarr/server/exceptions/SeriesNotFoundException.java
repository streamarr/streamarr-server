package com.streamarr.server.exceptions;

import java.util.UUID;

public class SeriesNotFoundException extends RuntimeException {

  public SeriesNotFoundException(UUID seriesId) {
    super("Series not found: " + seriesId);
  }
}
