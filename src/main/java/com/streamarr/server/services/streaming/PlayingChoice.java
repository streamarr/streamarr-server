package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.Optional;
import lombok.NonNull;

/**
 * The playback choice that a playback session plays now, and why its output differs from the output
 * that the requested playback choice asked for (ADR 0052).
 */
record PlayingChoice(
    @NonNull VideoQuality playbackChoice, @NonNull Optional<PlaybackFormatChange> formatChange) {

  /** The requested playback choice, before any format change. */
  static PlayingChoice requested(@NonNull VideoQuality playbackChoice) {
    return new PlayingChoice(playbackChoice, Optional.empty());
  }
}
