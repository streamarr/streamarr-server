package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * The exact measurement of one video or audio stream (ADR 0054). {@code measuredBytes} sums the
 * stream's packet payloads, and {@code measuredDuration} counts ticks of {@code timeBaseNumerator /
 * timeBaseDenominator} seconds from the stream's first presentation time to the end of its last
 * packet.
 */
@Builder
public record MeasuredStream(
    int streamIndex,
    long measuredBytes,
    long packetCount,
    int timeBaseNumerator,
    int timeBaseDenominator,
    long measuredDuration,
    @NonNull KeyframeIntervalCounts keyframeIntervalBytes) {

  public MeasuredStream {
    if (streamIndex < 0
        || measuredBytes < 0
        || packetCount < 0
        || measuredDuration < 0
        || timeBaseNumerator <= 0
        || timeBaseDenominator <= 0) {
      throw new IllegalArgumentException(
          "A measured stream needs a stream index, counts that are not negative and a positive"
              + " time base");
    }
  }
}
