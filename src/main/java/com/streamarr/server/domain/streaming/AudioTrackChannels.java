package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.NonNull;

/**
 * An audio track's channel count and its channel layout as ffprobe names it, such as {@code 7.0}.
 * The layout is empty when the probe stored none (ADR 0050).
 */
public record AudioTrackChannels(int count, @NonNull Optional<String> layout) {

  private static final int STEREO = 2;

  /**
   * The first audio stream's channels. A probe that reports no channel count, or zero, says no more
   * than a stereo track would.
   */
  public static AudioTrackChannels of(@NonNull MediaProbe source) {
    var count = source.audioChannels().orElse(STEREO);
    if (count <= 0) {
      count = STEREO;
    }

    return new AudioTrackChannels(count, source.audioChannelLayout());
  }
}
