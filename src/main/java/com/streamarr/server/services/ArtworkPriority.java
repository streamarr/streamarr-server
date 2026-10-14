package com.streamarr.server.services;

/**
 * Movie, series, season, and episode artwork is primary artwork, which scans and refreshes wait
 * for. Person and company artwork is background artwork, which waits behind it.
 */
public enum ArtworkPriority {
  PRIMARY,
  BACKGROUND
}
