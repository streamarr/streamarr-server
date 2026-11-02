package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * One stream's packets in one bitrate scan chunk (ADR 0054). {@code keyframeIntervalBytes} and
 * {@code keyframeIntervalPacketCounts} hold the stream's bytes and packets in each of the chunk's
 * keyframe intervals. {@code packetTimes} counts ticks of the stream's own time base and is empty
 * when the chunk holds no packet of the stream.
 */
@Builder
public record MeasuredChunkStream(
    int streamIndex,
    long measuredBytes,
    long packetCount,
    int timeBaseNumerator,
    int timeBaseDenominator,
    @NonNull Optional<PacketTimes> packetTimes,
    @NonNull KeyframeIntervalCounts keyframeIntervalBytes,
    @NonNull KeyframeIntervalCounts keyframeIntervalPacketCounts) {

  /** The stream's earliest presentation time in the chunk and the end of its last packet. */
  public record PacketTimes(long firstPresentationTime, long endTime) {}
}
