package com.streamarr.server.domain.language;

/** The level that an effective language preference comes from. */
public enum PreferenceSource {
  PROFILE,
  SERIES,
  SEASON,
  /** The episode or movie itself. */
  TITLE
}
