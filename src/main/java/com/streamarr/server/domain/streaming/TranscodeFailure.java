package com.streamarr.server.domain.streaming;

/**
 * A job attempt failure that every replacement attempt with the same decision would repeat, so no
 * producer of the same stream session can repair it.
 */
public enum TranscodeFailure {
  OUTPUT_MISMATCH,
  INVALID_SPECIFICATION,
  /**
   * A segment on the stream session's segment list exceeded the segment cap. Every attempt on the
   * same list cuts the same segment, so only a new format attempt on the plain grid avoids it (ADR
   * 0037).
   */
  SEGMENT_CAP_EXCEEDED
}
