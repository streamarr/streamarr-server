package com.streamarr.server.services.library;

import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * Plans a file's bitrate scan chunks (ADR 0054). The server stores keyframe times, not byte
 * offsets, so a chunk spans about {@link #TARGET_CHUNK_BYTES} of the file by its share of the
 * file's duration.
 */
final class BitrateScanChunks {

  static final long TARGET_CHUNK_BYTES = 1L << 30;
  // One chunk's intervals then fit one stream measurement part, which holds at most 4,096 entries.
  static final int MAXIMUM_KEYFRAME_INTERVALS = 4_096;

  private BitrateScanChunks() {}

  /**
   * A chunk of {@code keyframeIntervalCount} keyframe intervals from {@code firstKeyframeInterval}.
   */
  record PlannedChunk(int firstKeyframeInterval, int keyframeIntervalCount) {}

  /**
   * What the plan reads from the file, and from the scanner that measures its chunks: how many
   * streams a chunk measures, and the most keyframe intervals, summed over those streams, that the
   * scanner takes back from one chunk.
   */
  @Builder
  record File(
      @NonNull KeyframePositions positions,
      long size,
      @NonNull Optional<Duration> containerDuration,
      int measuredStreamCount,
      int measuredKeyframeIntervalLimit) {}

  /**
   * The chunk from {@code firstKeyframeInterval}. It holds at least one interval, at most {@link
   * #MAXIMUM_KEYFRAME_INTERVALS}, and no more than keep the intervals of every measured stream
   * within the file's measured keyframe interval limit. Within those bounds it grows interval by
   * interval while its decode-time span stays below the file's duration share of {@link
   * #TARGET_CHUNK_BYTES}. The last interval runs to the end of the file. When the container's
   * duration is unknown, the last keyframe's decode time stands in for it.
   */
  static PlannedChunk next(File file, int firstKeyframeInterval) {
    var keyframes = file.positions().keyframes();
    var start = keyframes.get(firstKeyframeInterval).decodeTime();
    var target = targetSpan(file);
    var mostIntervals = mostIntervals(file);
    var end = firstKeyframeInterval + 1;
    while (end < keyframes.size()
        && end - firstKeyframeInterval < mostIntervals
        && BigInteger.valueOf(endOf(file, end + 1))
                .subtract(BigInteger.valueOf(start))
                .compareTo(target)
            < 0) {
      end++;
    }

    return new PlannedChunk(firstKeyframeInterval, end - firstKeyframeInterval);
  }

  // The chunk's outcome holds one interval of every measured stream for each of its keyframes.
  private static int mostIntervals(File file) {
    return Math.clamp(
        file.measuredKeyframeIntervalLimit() / file.measuredStreamCount(),
        1,
        MAXIMUM_KEYFRAME_INTERVALS);
  }

  /** How many keyframe intervals from the first the stored chunks cover without a gap. */
  static int coveredIntervals(List<StoredBitrateScanChunk> stored) {
    var covered = 0;
    for (var chunk : stored) {
      if (chunk.firstKeyframeInterval() != covered) {
        return covered;
      }

      covered += chunk.keyframeIntervalCount();
    }

    return covered;
  }

  // The decode time where the interval before keyframe index ends: the keyframe's own time, or the
  // end of the file after the last keyframe.
  private static long endOf(File file, int keyframeIndex) {
    var keyframes = file.positions().keyframes();
    if (keyframeIndex < keyframes.size()) {
      return keyframes.get(keyframeIndex).decodeTime();
    }

    return durationTicks(file);
  }

  private static BigInteger targetSpan(File file) {
    return BigInteger.valueOf(durationTicks(file))
        .multiply(BigInteger.valueOf(TARGET_CHUNK_BYTES))
        .divide(BigInteger.valueOf(Math.max(file.size(), 1)));
  }

  private static long durationTicks(File file) {
    var positions = file.positions();
    var lastKeyframe = positions.keyframes().getLast();
    return file.containerDuration()
        .map(
            duration ->
                BigInteger.valueOf(duration.toNanos())
                    .multiply(BigInteger.valueOf(positions.timeBaseDenominator()))
                    .divide(
                        BigInteger.valueOf(positions.timeBaseNumerator())
                            .multiply(BigInteger.valueOf(1_000_000_000L)))
                    .longValueExact())
        .orElse(lastKeyframe.decodeTime());
  }

  static List<KeyframePosition> keyframesOf(KeyframePositions positions, PlannedChunk chunk) {
    return positions
        .keyframes()
        .subList(
            chunk.firstKeyframeInterval(),
            chunk.firstKeyframeInterval() + chunk.keyframeIntervalCount());
  }
}
