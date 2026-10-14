package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import lombok.NonNull;

/**
 * Movie, series, season, and episode artwork is primary artwork, which scans and refreshes wait
 * for. Person and company artwork is background artwork, which runs on its own scheduler and waits
 * behind primary artwork for processing.
 */
public enum ArtworkPriority {
  PRIMARY,
  BACKGROUND;

  public static ArtworkPriority of(@NonNull ImageEntityType itemType) {
    return switch (itemType) {
      case MOVIE, SERIES, SEASON, EPISODE -> PRIMARY;
      case PERSON, COMPANY -> BACKGROUND;
    };
  }
}
