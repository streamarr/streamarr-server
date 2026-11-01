package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import lombok.Builder;
import lombok.NonNull;

/**
 * The exact measurement of one video or audio stream (ADR 0054). {@code measuredBytes} sums the
 * stream's packet payloads, and {@code measuredDuration} counts ticks of {@code timeBaseNumerator /
 * timeBaseDenominator} seconds from the stream's first presentation time to the end of its last
 * packet. {@code keyframeIntervalBytes} and {@code keyframeIntervalPacketCounts} hold the stream's
 * bytes and packets in each keyframe interval.
 */
@Builder
public record MeasuredStream(
    int streamIndex,
    long measuredBytes,
    long packetCount,
    int timeBaseNumerator,
    int timeBaseDenominator,
    long measuredDuration,
    @NonNull KeyframeIntervalCounts keyframeIntervalBytes,
    @NonNull KeyframeIntervalCounts keyframeIntervalPacketCounts) {

  public MeasuredStream {
    if (anyIsNegative(streamIndex, measuredBytes, packetCount, measuredDuration)
        || anyIsNotPositive(timeBaseNumerator, timeBaseDenominator)) {
      throw new IllegalArgumentException(
          "A measured stream needs a stream index, counts that are not negative and a positive"
              + " time base");
    }

    if (keyframeIntervalPacketCounts.intervalCount() != keyframeIntervalBytes.intervalCount()) {
      throw new IllegalArgumentException(
          "A measured stream needs its packets and its bytes in the same keyframe intervals");
    }
  }

  private static boolean anyIsNegative(long... values) {
    return Arrays.stream(values).anyMatch(value -> value < 0);
  }

  private static boolean anyIsNotPositive(long... values) {
    return Arrays.stream(values).anyMatch(value -> value <= 0);
  }
}
