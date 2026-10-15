package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The keyframes of a source's chosen video stream in decode-time order. Times count ticks of {@code
 * timeBaseNumerator / timeBaseDenominator} seconds.
 */
@Builder
public record KeyframePositions(
    int streamIndex,
    int timeBaseNumerator,
    int timeBaseDenominator,
    @NonNull KeyframePositionSource source,
    @NonNull List<KeyframePosition> keyframes) {

  public KeyframePositions {
    if (streamIndex < 0 || timeBaseNumerator <= 0 || timeBaseDenominator <= 0) {
      throw new IllegalArgumentException(
          "Keyframe positions need a stream index and a positive time base");
    }

    if (keyframes.isEmpty()) {
      throw new IllegalArgumentException("Keyframe positions need at least one keyframe");
    }

    keyframes = List.copyOf(keyframes);
  }
}
