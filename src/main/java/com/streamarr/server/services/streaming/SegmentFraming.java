package com.streamarr.server.services.streaming;

import java.math.BigInteger;

/**
 * The bytes of a stream copy's delivered media segments outside their samples (ADR 0054). A media
 * segment is one moof and one mdat (worker_session.proto, MeasuredStream): the moof header, the
 * mfhd and the mdat header; then for each track a traf header, a tfhd, a version 1 tfdt and the
 * trun header; and a trun entry for each sample.
 */
final class SegmentFraming {

  private static final BigInteger SEGMENT_BYTES = BigInteger.valueOf(32);
  private static final BigInteger TRACK_BYTES = BigInteger.valueOf(64);
  private static final BigInteger SAMPLE_BYTES = BigInteger.valueOf(16);

  private SegmentFraming() {}

  /** The framing that this many segments carry for themselves, apart from their tracks. */
  static BigInteger segmentBytes(BigInteger segments) {
    return segments.multiply(SEGMENT_BYTES);
  }

  /** The framing of one track in this many segments that hold this many of its samples. */
  static BigInteger trackBytes(BigInteger segments, BigInteger samples) {
    return segments.multiply(TRACK_BYTES).add(samples.multiply(SAMPLE_BYTES));
  }
}
