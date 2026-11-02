package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** Bitrate scan chunks of one video stream on a 90 kHz time base with one-second intervals. */
@UtilityClass
public class BitrateScanChunkFixture {

  /** A chunk of {@code intervals} intervals with one packet of 100 bytes in each. */
  public static StoredBitrateScanChunk chunk(int firstKeyframeInterval, int intervals) {
    var bytes = new long[intervals];
    var packets = new long[intervals];
    Arrays.fill(bytes, 100);
    Arrays.fill(packets, 1);
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
                                firstKeyframeInterval * 90_000L,
                                (firstKeyframeInterval + intervals) * 90_000L)))
                    .keyframeIntervalBytes(KeyframeIntervalCounts.of(bytes))
                    .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(packets))
                    .build()))
        .build();
  }
}
