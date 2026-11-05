package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.PeakSegmentBitRate.SegmentSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The segment cap, a constant of the worker contract that the server and the worker each fix in
 * code: no delivered segment holds more bytes (ADR 0037).
 */
public final class SegmentCap {

  public static final int BYTES = 128 * 1024 * 1024;

  // The fixed heap cost that the worker's producer reserves for each sample it holds (ADR 0037).
  private static final BigInteger PRODUCER_HEAP_BYTES_PER_SAMPLE = BigInteger.valueOf(128);

  private SegmentCap() {}

  /**
   * Whether any interval's segments exceed the cap. The producer holds an interval's video segment
   * and its audio segment together, so their bytes, with their framing, count together with the
   * heap cost of their samples (ADR 0037). {@code audio} lists one size for each video segment, or
   * none when the copy carries no audio that the server can bound.
   */
  static boolean isExceededBy(List<SegmentSize> video, List<SegmentSize> audio) {
    var cap = BigDecimal.valueOf(BYTES);
    return IntStream.range(0, video.size())
        .mapToObj(segment -> intervalCost(video, audio, segment))
        .anyMatch(cost -> cost.compareTo(cap) > 0);
  }

  private static BigDecimal intervalCost(
      List<SegmentSize> video, List<SegmentSize> audio, int segment) {
    var cost = costOf(video.get(segment));
    if (!audio.isEmpty()) {
      cost = cost.add(costOf(audio.get(segment)));
    }

    return cost;
  }

  private static BigDecimal costOf(SegmentSize size) {
    return size.bytes()
        .add(new BigDecimal(size.samples().multiply(PRODUCER_HEAP_BYTES_PER_SAMPLE)));
  }
}
