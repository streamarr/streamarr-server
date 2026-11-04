package com.streamarr.server.domain.language;

/** When a Profile wants subtitles shown (ADR 0043). */
public enum SubtitlePreferenceMode {
  OFF,
  /** Only forced subtitles, the default. */
  FORCED,
  ALWAYS,
  /** Subtitles whenever the audio is in none of the Profile's audio languages. */
  FOREIGN_AUDIO
}
