package com.streamarr.server.services.streaming;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RFC 8216 §4.1's peak segment bit rate: the largest bit rate of any run of consecutive media
 * segments whose EXTINF durations sum to between 0.5 and 1.5 times the target duration.
 */
final class PeakSegmentBitRate {

  private static final BigDecimal SHORTEST_RUN_TARGETS = new BigDecimal("0.5");
  private static final BigDecimal LONGEST_RUN_TARGETS = new BigDecimal("1.5");
  private static final BigInteger LARGEST_BIT_RATE = BigInteger.valueOf(Long.MAX_VALUE);

  private PeakSegmentBitRate() {}

  /**
   * The bytes that a media segment delivers for one track, its EXTINF duration, and the samples of
   * the track that it holds.
   */
  record SegmentSize(BigDecimal bytes, BigDecimal extinfSeconds, BigInteger samples) {}

  /**
   * The peak in bits per second, rounded up and at most {@link Long#MAX_VALUE}, of segments whose
   * EXTINF durations sum to more than zero, under the EXT-X-TARGETDURATION that their media
   * playlist writes.
   */
  static long of(List<SegmentSize> segments, int targetDurationSeconds) {
    return runBitRates(segments, targetDurationSeconds).stream()
        .max(Comparator.naturalOrder())
        .orElseGet(() -> wholePlaylistBitRate(segments))
        .min(LARGEST_BIT_RATE)
        .longValueExact();
  }

  /** The bit rate of every run that lasts 0.5 to 1.5 times the target duration. */
  private static List<BigInteger> runBitRates(
      List<SegmentSize> segments, int targetDurationSeconds) {
    var target = BigDecimal.valueOf(targetDurationSeconds);
    var shortestRun = target.multiply(SHORTEST_RUN_TARGETS);
    var longestRun = target.multiply(LONGEST_RUN_TARGETS);
    var bitRates = new ArrayList<BigInteger>();
    for (var first = 0; first < segments.size(); first++) {
      var bytes = BigDecimal.ZERO;
      var seconds = BigDecimal.ZERO;
      for (var last = first; last < segments.size(); last++) {
        bytes = bytes.add(segments.get(last).bytes());
        seconds = seconds.add(segments.get(last).extinfSeconds());
        if (seconds.compareTo(longestRun) > 0) {
          break;
        }

        if (seconds.compareTo(shortestRun) >= 0) {
          bitRates.add(bitRate(bytes, seconds));
        }
      }
    }

    return bitRates;
  }

  // RFC 8216 defines no peak for a playlist shorter than half the target duration, where no run
  // qualifies. The whole playlist is then the one run.
  private static BigInteger wholePlaylistBitRate(List<SegmentSize> segments) {
    var bytes = BigDecimal.ZERO;
    var seconds = BigDecimal.ZERO;
    for (var segment : segments) {
      bytes = bytes.add(segment.bytes());
      seconds = seconds.add(segment.extinfSeconds());
    }

    return bitRate(bytes, seconds);
  }

  private static BigInteger bitRate(BigDecimal bytes, BigDecimal seconds) {
    return bytes
        .multiply(BigDecimal.valueOf(8))
        .divide(seconds, 0, RoundingMode.CEILING)
        .toBigIntegerExact();
  }
}
