package com.streamarr.server.domain.language;

/** When a Profile wants subtitles shown (ADR 0043). */
public enum SubtitlePreferenceMode {
  /** No subtitle track, not even a forced track. */
  OFF,
  /** Only a forced track in the audio's language, the default. */
  FORCED,
  /** The track in the subtitle language, whatever the audio. */
  ALWAYS
}
