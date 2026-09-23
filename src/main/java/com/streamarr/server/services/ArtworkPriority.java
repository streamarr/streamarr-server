package com.streamarr.server.services;

/**
 * Movie, series, season, and episode artwork is required; person and company artwork is secondary
 * and waits behind it.
 */
public enum ArtworkPriority {
  REQUIRED,
  SECONDARY
}
