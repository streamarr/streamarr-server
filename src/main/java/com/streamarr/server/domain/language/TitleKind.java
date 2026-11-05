package com.streamarr.server.domain.language;

/** The kinds of title that a Profile can override its language preferences for. */
public enum TitleKind {
  MOVIE,
  SERIES,
  SEASON,
  EPISODE;

  /** The level that an override of a title of this kind sets. */
  public PreferenceSource source() {
    return switch (this) {
      case MOVIE, EPISODE -> PreferenceSource.TITLE;
      case SEASON -> PreferenceSource.SEASON;
      case SERIES -> PreferenceSource.SERIES;
    };
  }
}
