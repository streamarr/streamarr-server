package com.streamarr.server.exceptions;

import java.util.UUID;

public class SeasonNotFoundException extends RuntimeException {

  public SeasonNotFoundException(UUID seasonId, UUID episodeId) {
    super("Season not found: " + seasonId + " (referenced by episode " + episodeId + ")");
  }
}
