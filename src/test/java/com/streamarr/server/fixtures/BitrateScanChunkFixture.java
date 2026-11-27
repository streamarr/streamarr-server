package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntToLongFunction;
import lombok.experimental.UtilityClass;

/** Bitrate scan chunks of one video stream on a 90 kHz time base with one-second intervals. */
@UtilityClass
public class BitrateScanChunkFixture {

  private static final long TICKS_PER_KEYFRAME = 90_000;

  /** A chunk of {@code intervals} intervals with one packet of 100 bytes in each. */
  public static StoredBitrateScanChunk chunk(int firstKeyframeInterval, int intervals) {
    return StoredBitrateScanChunk.builder()
        .firstKeyframeInterval(firstKeyframeInterval)
        .keyframeIntervalCount(intervals)
        .streams(
            List.of(
                MeasuredChunkStream.builder()
                    .streamIndex(0)
                    .measuredBytes(100L * intervals)
                    .packetCount(intervals)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator(90_000)
                    .packetTimes(
                        Optional.of(
                            new MeasuredChunkStream.PacketTimes(
                                firstKeyframeInterval * TICKS_PER_KEYFRAME,
                                (firstKeyframeInterval + intervals) * TICKS_PER_KEYFRAME)))
                    .keyframeIntervalBytes(repeated(100, intervals))
                    .keyframeIntervalPacketCounts(repeated(1, intervals))
                    .build()))
        .build();
  }

  /**
   * A worker's measurement of the chunk: one packet in every keyframe interval of each requested
   * stream, with the packet's size read from {@code bytesPerInterval} by stream index.
   */
  public static BitrateScanChunkOutcome measured(
      BitrateScanChunkRequest chunk, IntToLongFunction bytesPerInterval) {
    var intervals = chunk.keyframes().size();
    var first = chunk.keyframes().getFirst().presentationTime();
    var end =
        chunk
            .end()
            .map(KeyframePosition::presentationTime)
            .orElse(chunk.keyframes().getLast().presentationTime() + TICKS_PER_KEYFRAME);
    return new BitrateScanChunkOutcome.Measured(
        chunk.measuredStreamIndexes().stream()
            .map(
                index ->
                    MeasuredChunkStream.builder()
                        .streamIndex(index)
                        .measuredBytes(bytesPerInterval.applyAsLong(index) * intervals)
                        .packetCount(intervals)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(first, end)))
                        .keyframeIntervalBytes(
                            repeated(bytesPerInterval.applyAsLong(index), intervals))
                        .keyframeIntervalPacketCounts(repeated(1, intervals))
                        .build())
            .toList());
  }

  /** The same count in each of {@code intervals} keyframe intervals. */
  public static KeyframeIntervalCounts repeated(long count, int intervals) {
    var counts = new long[intervals];
    Arrays.fill(counts, count);
    return KeyframeIntervalCounts.of(counts);
  }
}
