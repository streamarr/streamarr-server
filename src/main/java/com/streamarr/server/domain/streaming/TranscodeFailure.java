package com.streamarr.server.domain.streaming;

/**
 * A job attempt failure that every replacement attempt with the same decision would repeat, so no
 * producer of the same stream session can repair it.
 */
public enum TranscodeFailure {
  OUTPUT_MISMATCH,
  /** The required copied audio ended before a segment whose video continues. */
  COPIED_AUDIO_RAN_SHORT,
  INVALID_SPECIFICATION,
  /**
   * A segment on the stream session's segment list exceeded the segment cap. Every attempt on the
   * same list cuts the same segment, so only a new format attempt on the plain grid avoids it (ADR
   * 0037).
   */
  SEGMENT_CAP_EXCEEDED,
  /**
   * A copy that converts single-track Dolby Vision profile 7 to profile 8.1 met a picture whose RPU
   * it cannot convert. Every attempt with the same decision meets the same picture, so only the
   * copy with Dolby Vision stripped, or an encode, avoids it (streamarr/streamarr-server#651).
   */
  DOLBY_VISION_CONVERSION_FAILED
}
