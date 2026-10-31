package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.NonNull;

/**
 * The audio that a stream session can deliver in place of its audio decision under a maximum
 * bitrate, highest first (ADR 0050). The first step is the session's audio as the audio decision
 * chose it. Each later step is an encode with a lower bitrate than every step before it.
 */
public record AudioSteps(@NonNull List<AudioDecision> highestFirst) {

  public AudioSteps {
    if (highestFirst.isEmpty()) {
      throw new IllegalArgumentException("Audio steps need the session's audio");
    }

    highestFirst = List.copyOf(highestFirst);
  }

  /** The stream session's audio as the audio decision chose it. */
  public AudioDecision sessionAudio() {
    return highestFirst.getFirst();
  }
}
